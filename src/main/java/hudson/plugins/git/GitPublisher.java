package hudson.plugins.git;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.FilePath.FileCallable;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.git.opt.PreBuildMergeOptions;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormFieldValidator;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.spearce.jgit.transport.RemoteConfig;

public class GitPublisher extends Publisher implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean needsToRunAfterFinalized() {
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build,
            Launcher launcher, final BuildListener listener)
            throws InterruptedException {

        final SCM scm = build.getProject().getScm();

        if (!(scm instanceof GitSCM)) {
            return false;
        }

        final String projectName = build.getProject().getName();
        final int buildNumber = build.getNumber();
        final Result buildResult = build.getResult();

        boolean canPerform;
        try {
            canPerform = build.getWorkspace().act(
                    new FileCallable<Boolean>() {

                        private static final long serialVersionUID = 1L;

                        public Boolean invoke(File workspace,
                                VirtualChannel channel) throws IOException {

                            GitSCM gitSCM = (GitSCM) scm;

                            EnvVars environment;
                            try {
                                environment = build.getEnvironment(listener);
                            } catch (IOException e) {
                                listener.error("IOException publishing in git plugin");
                                environment = new EnvVars();
                            } catch (InterruptedException e) {
                                listener.error("IOException publishing in git plugin");
                                environment = new EnvVars();
                            }
                            IGitAPI git = new GitAPI(
                                    gitSCM.getDescriptor().getGitExe(), build.getWorkspace(),
                                    listener, environment);

                            // We delete the old tag generated by the SCM plugin
                            String buildnumber = "hudson-" + projectName + "-" + buildNumber;
                            git.deleteTag(buildnumber);

                            // And add the success / fail state into the tag.
                            buildnumber += "-" + buildResult.toString();

                            git.tag(buildnumber, "Hudson Build #" + buildNumber);

                            PreBuildMergeOptions mergeOptions = gitSCM.getMergeOptions();

                            if (mergeOptions.doMerge() && buildResult.isBetterOrEqualTo(
                                    Result.SUCCESS)) {
                                RemoteConfig remote = mergeOptions.getMergeRemote();
                                listener.getLogger().println("Pushing result " + buildnumber + " to " + mergeOptions.getMergeTarget() + " branch of " + remote.getName() + " repository");

                                git.push(remote, "HEAD:" + mergeOptions.getMergeTarget());
                            } else {
                                //listener.getLogger().println("Pushing result " + buildnumber + " to origin repository");
                                //git.push(null);
                            }

                            return true;
                        }
                    });
        } catch (Throwable e) {
            listener.error("Failed to push tags to origin repository: " + e.getMessage());
            build.setResult(Result.FAILURE);
            return false;

        }
        return canPerform;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(GitPublisher.class);
        }

        public String getDisplayName() {
            return "Push Merges back to origin";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/git/gitPublisher.html";
        }

        /**
         * Performs on-the-fly validation on the file mask wildcard.
         *
         * @param req request
         * @param rsp response
         * @throws IOException
         * @throws ServletException
         */
        public void doCheck(StaplerRequest req, StaplerResponse rsp)
                throws IOException, ServletException {
            new FormFieldValidator.WorkspaceFileMask(req, rsp).process();
        }

        @Override
        public GitPublisher newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            return new GitPublisher();
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
