/*
 *  [2012] - [2016] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.plugin.contribution.client.parts.contribute;

import com.codenvy.plugin.contribution.client.ContributeMessages;
import com.codenvy.plugin.contribution.client.steps.CommitWorkingTreeStep;
import com.codenvy.plugin.contribution.client.steps.Context;
import com.codenvy.plugin.contribution.client.steps.ContributorWorkflow;
import com.codenvy.plugin.contribution.client.steps.Step;
import com.codenvy.plugin.contribution.client.steps.events.ContextPropertyChangeEvent;
import com.codenvy.plugin.contribution.client.steps.events.ContextPropertyChangeHandler;
import com.codenvy.plugin.contribution.client.steps.events.StepEvent;
import com.codenvy.plugin.contribution.client.steps.events.StepHandler;
import com.codenvy.plugin.contribution.client.utils.FactoryHelper;
import com.codenvy.plugin.contribution.client.utils.NotificationHelper;
import com.codenvy.plugin.contribution.vcs.client.VcsServiceProvider;
import com.codenvy.plugin.contribution.vcs.client.hosting.VcsHostingService;
import com.codenvy.plugin.contribution.vcs.client.hosting.VcsHostingServiceProvider;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.AcceptsOneWidget;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.factory.shared.dto.Factory;
import org.eclipse.che.api.git.shared.Branch;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.parts.WorkspaceAgent;
import org.eclipse.che.ide.api.parts.base.BasePresenter;
import org.eclipse.che.ide.ui.dialogs.CancelCallback;
import org.eclipse.che.ide.ui.dialogs.DialogFactory;
import org.eclipse.che.ide.ui.dialogs.InputCallback;
import org.eclipse.che.ide.ui.dialogs.input.InputValidator;
import org.eclipse.che.ide.util.loging.Log;

import javax.validation.constraints.NotNull;

import org.eclipse.che.commons.annotation.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.eclipse.che.ide.api.constraints.Constraints.LAST;
import static org.eclipse.che.ide.api.parts.PartStackType.TOOLING;

/**
 * Part for the contribution configuration.
 *
 * @author Kevin Pollet
 */
public class ContributePartPresenter extends BasePresenter
        implements ContributePartView.ActionDelegate, StepHandler, ContextPropertyChangeHandler {
    private final ContributePartView        view;
    private final WorkspaceAgent            workspaceAgent;
    private final ContributeMessages        messages;
    private final ContributorWorkflow       workflow;
    private final VcsHostingServiceProvider vcsHostingServiceProvider;
    private final Step                      commitWorkingTreeStep;
    private final AppContext                appContext;
    private final VcsServiceProvider        vcsServiceProvider;
    private final NotificationHelper        notificationHelper;
    private final DialogFactory             dialogFactory;

    @Inject
    public ContributePartPresenter(@NotNull final ContributePartView view,
                                   @NotNull final ContributeMessages messages,
                                   @NotNull final WorkspaceAgent workspaceAgent,
                                   @NotNull final EventBus eventBus,
                                   @NotNull final ContributorWorkflow workflow,
                                   @NotNull final VcsHostingServiceProvider vcsHostingServiceProvider,
                                   @NotNull final CommitWorkingTreeStep commitWorkingTreeStep,
                                   @NotNull final AppContext appContext,
                                   @NotNull final VcsServiceProvider vcsServiceProvider,
                                   @NotNull final NotificationHelper notificationHelper,
                                   @NotNull final DialogFactory dialogFactory) {
        this.view = view;
        this.workspaceAgent = workspaceAgent;
        this.workflow = workflow;
        this.vcsHostingServiceProvider = vcsHostingServiceProvider;
        this.messages = messages;
        this.commitWorkingTreeStep = commitWorkingTreeStep;
        this.appContext = appContext;
        this.vcsServiceProvider = vcsServiceProvider;
        this.notificationHelper = notificationHelper;
        this.dialogFactory = dialogFactory;

        this.view.setDelegate(this);
        eventBus.addHandler(StepEvent.TYPE, this);
        eventBus.addHandler(ContextPropertyChangeEvent.TYPE, this);
    }

    public void open() {
        view.setRepositoryUrl("");
        view.setClonedBranch("");
        view.setContributionBranchName("");
        view.setContributionBranchNameEnabled(true);
        view.setContributionBranchNameList(Collections.<String>emptyList());
        view.setContributionTitle("");
        view.setProjectName("");
        view.setContributionTitleEnabled(true);
        view.setContributionComment("");
        view.setContributionCommentEnabled(true);
        view.setContributeButtonText(messages.contributePartConfigureContributionSectionButtonContributeText());
        view.hideStatusSection();
        view.hideNewContributionSection();

        updateControls();

        workspaceAgent.openPart(ContributePartPresenter.this, TOOLING, LAST);
    }

    public void remove() {
        workspaceAgent.removePart(ContributePartPresenter.this);
    }

    @Override
    public void onContribute() {
        if (workflow.getContext().isUpdateMode()) {
            view.showStatusSection(messages.contributePartStatusSectionNewCommitsPushedStepLabel(),
                                   messages.contributePartStatusSectionPullRequestUpdatedStepLabel());

        } else {
            if (workflow.getContext().hasForkSupport()) {
                view.showStatusSection(messages.contributePartStatusSectionForkCreatedStepLabel(),
                                       messages.contributePartStatusSectionBranchPushedForkStepLabel(),
                                       messages.contributePartStatusSectionPullRequestIssuedStepLabel());
            } else {
                view.showStatusSection(messages.contributePartStatusSectionBranchPushedOriginStepLabel(),
                                       messages.contributePartStatusSectionPullRequestIssuedStepLabel());
            }
        }

        view.hideStatusSectionMessage();
        view.setContributeButtonEnabled(false);
        view.setContributionProgressState(true);

        // resume the contribution workflow and execute the commit tree step
        workflow.getConfiguration()
                .withContributionBranchName(view.getContributionBranchName())
                .withContributionComment(view.getContributionComment())
                .withContributionTitle(view.getContributionTitle());

        workflow.setStep(commitWorkingTreeStep);
        workflow.executeStep();
    }


    @Override
    public void onOpenPullRequestOnVcsHost() {
        final Context context = workflow.getContext();

        context.getVcsHostingService().makePullRequestUrl(context.getUpstreamRepositoryOwner(), context.getUpstreamRepositoryName(),
                                                          context.getPullRequestIssueNumber()).then(new Operation<String>() {
            @Override
            public void apply(String url) throws OperationException {
                Window.open(url, "", "");
            }
        }).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError error) throws OperationException {
                notificationHelper.showError(ContributePartPresenter.class, error.getCause());
            }
        });
    }

    @Override
    public void onNewContribution() {
        final Context context = workflow.getContext();
        vcsServiceProvider.getVcsService().checkoutBranch(context.getProject(), context.getClonedBranchName(),
                                                          false, new AsyncCallback<String>() {
                    @Override
                    public void onFailure(final Throwable exception) {
                        notificationHelper.showError(ContributePartPresenter.class, exception);
                    }

                    @Override
                    public void onSuccess(final String branchName) {
                        view.setContributionBranchName(context.getClonedBranchName());
                        view.setContributionBranchNameEnabled(true);
                        view.setContributionTitle("");
                        view.setContributionTitleEnabled(true);
                        view.setContributionComment("");
                        view.setContributionCommentEnabled(true);
                        view.setContributeButtonText(messages.contributePartConfigureContributionSectionButtonContributeText());
                        view.hideStatusSection();
                        view.hideStatusSectionMessage();
                        view.hideNewContributionSection();

                        workflow.getContext().setUpdateMode(false);
                        updateControls();

                        notificationHelper
                                .showInfo(messages.contributePartNewContributionBranchClonedCheckedOut(context.getClonedBranchName()));
                    }
                });
    }

    @Override
    public void onRefreshContributionBranchNameList() {
        refreshContributionBranchNameList();
    }

    @Override
    public void onCreateNewBranch() {
        dialogFactory.createInputDialog(messages.contributePartConfigureContributionDialogNewBranchTitle(),
                                        messages.contributePartConfigureContributionDialogNewBranchLabel(),
                                        new CreateNewBranchCallback(),
                                        new CancelNewBranchCallback())
                     .withValidator(new BranchNameValidator())
                     .show();
    }

    @Override
    public void updateControls() {
        final String contributionTitle = view.getContributionTitle();

        boolean isValid = true;
        view.showContributionTitleError(false);

        if (contributionTitle == null || contributionTitle.trim().isEmpty()) {
            view.showContributionTitleError(true);
            isValid = false;
        }

        view.setContributeButtonEnabled(isValid);
    }

    @Override
    public void go(final AcceptsOneWidget container) {
        container.setWidget(view.asWidget());
    }

    @NotNull
    @Override
    public String getTitle() {
        return messages.contributePartTitle();
    }

    @Override
    public void setVisible(boolean visible) {

    }

    @Override
    public IsWidget getView() {
        return view;
    }

    @Nullable
    @Override
    public ImageResource getTitleImage() {
        return null;
    }

    @Nullable
    @Override
    public String getTitleToolTip() {
        return null;
    }

    @Override
    public int getSize() {
        return 350;
    }

    @Override
    public void onStepDone(@NotNull final StepEvent event) {
        switch (event.getStep()) {
            case CREATE_FORK: {
                if (!workflow.getContext().isUpdateMode()) {
                    view.setCurrentStatusStepStatus(true);
                }
            }
            break;

            case PUSH_BRANCH_ON_FORK:
            case PUSH_BRANCH_ON_ORIGIN: {
                view.setCurrentStatusStepStatus(true);
            }
            break;

            case ISSUE_PULL_REQUEST: {
                view.setCurrentStatusStepStatus(true);
                view.setContributeButtonEnabled(true);
                view.setContributionProgressState(false);
                view.showStatusSectionMessage(
                        workflow.getContext().isUpdateMode() ? messages.contributePartStatusSectionContributionUpdatedMessage()
                                                             : messages.contributePartStatusSectionContributionCreatedMessage(), false);
                view.setContributionBranchNameEnabled(false);
                view.setContributionTitleEnabled(false);
                view.setContributionCommentEnabled(false);
                view.setContributeButtonText(messages.contributePartConfigureContributionSectionButtonContributeUpdateText());
                workflow.getContext().setUpdateMode(true);

                vcsHostingServiceProvider.getVcsHostingService(new AsyncCallback<VcsHostingService>() {
                    @Override
                    public void onFailure(final Throwable exception) {
                        notificationHelper.showError(ContributePartPresenter.class, exception);
                    }

                    @Override
                    public void onSuccess(final VcsHostingService vcsHostingService) {
                        view.showNewContributionSection(vcsHostingService.getName());
                    }
                });
            }
            break;

            default:
                break;
        }
    }

    @Override
    public void onStepError(@NotNull final StepEvent event) {
        switch (event.getStep()) {
            case AUTHORIZE_CODENVY_ON_VCS_HOST:
                view.hideStatusSection();
                view.hideStatusSectionMessage();
                view.hideNewContributionSection();
                break;
            case COMMIT_WORKING_TREE: {
                view.hideStatusSection();
            }
            break;

            case CREATE_FORK: {
                if (!workflow.getContext().isUpdateMode()) {
                    view.setCurrentStatusStepStatus(false);
                    view.showStatusSectionMessage(event.getMessage(), true);
                }
            }
            break;

            case CHECKOUT_BRANCH_TO_PUSH: {
                view.setCurrentStatusStepStatus(false);
                view.showStatusSectionMessage(event.getMessage(), true);
            }
            break;

            case PUSH_BRANCH_ON_FORK:
            case PUSH_BRANCH_ON_ORIGIN:
            case ISSUE_PULL_REQUEST: {
                view.setCurrentStatusStepStatus(false);
                view.showStatusSectionMessage(event.getMessage(), true);
            }
            break;

            default:
                Log.error(ContributePartPresenter.class, "Step error:", event.getMessage());
                break;
        }

        view.setContributeButtonEnabled(true);
        view.setContributionProgressState(false);
    }

    @Override
    public void onContextPropertyChange(final ContextPropertyChangeEvent event) {
        final Context context = event.getContext();

        switch (event.getContextProperty()) {
            case CLONED_BRANCH_NAME: {
                view.setClonedBranch(context.getClonedBranchName());
                view.setContributionBranchName(context.getClonedBranchName());
            }
            break;

            case WORK_BRANCH_NAME: {
                refreshContributionBranchNameList(new AsyncCallback<Void>() {
                    @Override
                    public void onFailure(final Throwable exception) {
                        notificationHelper.showError(ContributePartPresenter.class, exception);
                    }

                    @Override
                    public void onSuccess(final Void notUsed) {
                        view.setContributionBranchName(context.getWorkBranchName());
                    }
                });
            }
            break;

            case ORIGIN_REPOSITORY_NAME:
            case ORIGIN_REPOSITORY_OWNER: {
                final String originRepositoryName = context.getOriginRepositoryName();
                final String originRepositoryOwner = context.getOriginRepositoryOwner();

                if (originRepositoryName != null && originRepositoryOwner != null) {
                    vcsHostingServiceProvider.getVcsHostingService(new AsyncCallback<VcsHostingService>() {
                        @Override
                        public void onFailure(final Throwable exception) {
                            notificationHelper.showError(ContributePartPresenter.class, exception);
                        }

                        @Override
                        public void onSuccess(final VcsHostingService vcsHostingService) {
                            vcsHostingService.makeHttpRemoteUrl(originRepositoryOwner, originRepositoryName)
                                             .then(new Operation<String>() {
                                                 @Override
                                                 public void apply(String arg) throws OperationException {
                                                     view.setRepositoryUrl(arg);
                                                 }
                                             });
                        }
                    });
                }
            }
            break;

            case PROJECT: {
                view.setProjectName(event.getContext().getProject().getName());
                refreshContributionBranchNameList();
            }
            break;

            default:
                break;
        }
    }

    private void refreshContributionBranchNameList() {
        refreshContributionBranchNameList(new AsyncCallback<Void>() {
            @Override
            public void onFailure(final Throwable exception) {
                notificationHelper.showError(ContributePartPresenter.class, exception);
            }

            @Override
            public void onSuccess(final Void notUsed) {
                // nothing to do branch name list is refreshed
            }
        });
    }

    private void refreshContributionBranchNameList(final AsyncCallback<Void> callback) {
        vcsServiceProvider.getVcsService().listLocalBranches(workflow.getContext().getProject(), new AsyncCallback<List<Branch>>() {
            @Override
            public void onFailure(final Throwable exception) {
                callback.onFailure(exception);
            }

            @Override
            public void onSuccess(final List<Branch> branches) {
                final List<String> branchNames = new ArrayList<>();
                for (final Branch oneBranch : branches) {
                    branchNames.add(oneBranch.getDisplayName());
                }

                view.setContributionBranchNameList(branchNames);
                callback.onSuccess(null);
            }
        });
    }

    private static class BranchNameValidator implements InputValidator {
        private static final Violation ERROR_WITH_NO_MESSAGE = new Violation() {
            @Nullable
            @Override
            public String getMessage() {
                return "";
            }

            @Nullable
            @Override
            public String getCorrectedValue() {
                return null;
            }
        };

        @Nullable
        @Override
        public Violation validate(final String branchName) {
            return branchName.matches("[0-9A-Za-z-]+") ? null : ERROR_WITH_NO_MESSAGE;
        }
    }

    private class CreateNewBranchCallback implements InputCallback {
        @Override
        public void accepted(final String branchName) {
            final Context context = workflow.getContext();

            vcsServiceProvider.getVcsService().isLocalBranchWithName(context.getProject(), branchName, new AsyncCallback<Boolean>() {
                @Override
                public void onFailure(final Throwable exception) {
                    notificationHelper.showError(ContributePartPresenter.class, exception);
                }

                @Override
                public void onSuccess(final Boolean branchExists) {
                    if (branchExists) {
                        view.setContributionBranchName(branchName);
                        notificationHelper.showError(ContributePartPresenter.class,
                                                     messages.contributePartConfigureContributionDialogNewBranchErrorBranchExists(
                                                             branchName));

                    } else {
                        vcsServiceProvider.getVcsService().checkoutBranch(context.getProject(), branchName, true,
                                                                          new AsyncCallback<String>() {
                                                                              @Override
                                                                              public void onFailure(final Throwable exception) {

                                                                              }

                                                                              @Override
                                                                              public void onSuccess(final String notUsed) {
                                                                                  refreshContributionBranchNameList(
                                                                                          new AsyncCallback<Void>() {
                                                                                              @Override
                                                                                              public void onFailure(
                                                                                                      final Throwable exception) {
                                                                                                  notificationHelper.showError(
                                                                                                          ContributePartPresenter.class,
                                                                                                          exception);
                                                                                              }

                                                                                              @Override
                                                                                              public void onSuccess(final Void notUsed) {
                                                                                                  view.setContributionBranchName(
                                                                                                          branchName);
                                                                                              }
                                                                                          });
                                                                              }
                                                                          });
                    }
                }
            });
        }
    }

    private class CancelNewBranchCallback implements CancelCallback {
        @Override
        public void cancelled() {
            view.setContributionBranchName(workflow.getContext().getWorkBranchName());
        }
    }
}
