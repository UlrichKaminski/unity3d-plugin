package org.jenkinsci.plugins.unity3d;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.QuotedStringTokenizer;

import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import hudson.AbortException;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import java.util.HashMap;
import java.util.logging.Level;

import net.sf.json.JSONObject;
import org.jenkinsci.plugins.unity3d.io.Pipe;
import org.jenkinsci.plugins.unity3d.io.StreamCopyThread;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundSetter;


public class Unity3dBuilder extends Builder implements SimpleBuildStep {
    private static final Logger log = Logger.getLogger(Unity3dBuilder.class.getName());

    /**
     * @since 0.1
     */
    private String unity3dName;
    /**
     * @since 0.1
     */
    private String argLine;
    private String unstableReturnCodes;
    
    @DataBoundSetter
    public void setArgLine(String argLine) {
       this.argLine = argLine;
   }
    
     @DataBoundSetter
    public void setUnstableReturnCodes(String unstableReturnCodes) {
       this.unstableReturnCodes = unstableReturnCodes;
   }
    
    @DataBoundSetter
    public void setUnity3dName(String unity3dName) {
       this.unity3dName = unity3dName;
   }

    @DataBoundConstructor
    public Unity3dBuilder() {}

    @SuppressWarnings("unused")
    private Object readResolve() throws ObjectStreamException {
        if (unstableReturnCodes == null)
            unstableReturnCodes = "";
        return this;
    }

    /**
     * @return 
     * @since 0.1
     */
    public String getArgLine() {
        return argLine;
    }

    /**
     * @return 
     * @since 1.0
     */
    public String getUnstableReturnCodes() {
        return unstableReturnCodes;
    }

    Set<Integer> toUnstableReturnCodesSet() {
        return toIntegerSet(unstableReturnCodes);
    }

    private String getArgLineOrGlobalArgLine() {
        if (argLine != null && argLine.trim().length() > 0) {
            return argLine;
        } else {
            return getDescriptor().globalArgLine;
        }
    }

    public String getUnity3dName() {
        return unity3dName;
    }
    
    private static class PerformException extends Exception {
        private static final long serialVersionUID = 1L;
        
        private PerformException(String s) {
            super(s);
        }
    }
    
    @Override
   //public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener)
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        try {
            _perform(build, workspace, launcher, listener);
        } catch (PerformException e) {
            listener.fatalError(e.getMessage());
            throw new AbortException(e.getMessage());
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            String errorMessage = Messages.Unity3d_ExecUnexpectedlyFailed();
            e.printStackTrace(listener.fatalError(errorMessage));
            throw e;
        }
    }

    private void _perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException, PerformException {
        EnvVars env = build.getEnvironment(listener);
        
        Unity3dInstallation ui = getAndConfigureUnity3dInstallation(workspace, listener, env);

        ArgumentListBuilder args = prepareCommandlineArguments(build, workspace, launcher, ui, env);

        String customLogFile = findLogFileArgument(args);

        Pipe pipe = Pipe.createRemoteToLocal(launcher);

        PrintStream ca = listener.getLogger();
        ca.println("Piping unity Editor.log from " + ui.getEditorLogPath(launcher, customLogFile));
        Future<Long> futureReadBytes = ui.pipeEditorLog(launcher, customLogFile, pipe.getOut());
        // Unity3dConsoleAnnotator ca = new Unity3dConsoleAnnotator(listener.getLogger(), build.getCharset());

        StreamCopyThread copierThread = new StreamCopyThread("Pipe editor.log to output thread.", pipe.getIn(), ca);
        try {
            copierThread.start();
            int r = launcher.launch().cmds(args).envs(env).stdout(ca).pwd(workspace).join();
            // r == 11 means executeMethod could not be found ?
            checkProcResult(build, r);
        } finally {
            // give a bit of time for the piping to complete. Not really
            // sure why it's not properly flushed otherwise
            Thread.sleep(1000);
            if (!futureReadBytes.isDone()) {
                // NOTE According to the API, cancel() should cause future calls to get() to fail with an exception
                // Jenkins implementation doesn't seem to record it right now and just interrupts the remote task
                // but we won't use the value, in case that behavior changes, even for debugging / informative purposes
                // we still call cancel to stop the task.
                futureReadBytes.cancel(true);
                // listener.getLogger().print("Read " + futureReadBytes.get() + " bytes from Editor.log");
            }
            try {
                copierThread.join();
                if (copierThread.getFailure() != null) {
                   ca.println("Failure on remote ");
                   copierThread.getFailure().printStackTrace(ca);
                }
            }
            finally {
                //ca.forceEol();
            }
        }
    }

    private void checkProcResult(Run<?,?> build, int result) throws PerformException {
        log.log(Level.INFO, "Unity command line exited with error code: {0}", result);
        if (isBuildUnstable(result)) {
            log.info(Messages.Unity3d_BuildMarkedAsUnstableBecauseOfStatus(result));
            build.setResult(Result.UNSTABLE);
        } else if (!isBuildSuccess(result)) {
            throw new PerformException(Messages.Unity3d_UnityExecFailed(result));
        }
    }

    private boolean isBuildUnstable(int result) {
        Set<Integer> codes = toUnstableReturnCodesSet();
        return codes.size() > 0 && codes.contains(result);
    }

    private boolean isBuildSuccess(int result) {
        // we could add a set of success results as well, if needed.
        return result == 0 || result == 2 || result == 3;
    }

    /** Find the -logFile argument from the built arg line **/
    private String findLogFileArgument(ArgumentListBuilder args) {
        String customLogFile = null;
        List<String> a = args.toList();
        for (int i = 0; i < a.size() - 1; i++) {
            if (a.get(i).equals("-logFile")) {
                customLogFile = a.get(i+1);
            }
        }
        return customLogFile;
    }

    private ArgumentListBuilder prepareCommandlineArguments(Run<?,?> build, FilePath workspace, Launcher launcher, Unity3dInstallation ui, EnvVars vars) throws IOException, InterruptedException, PerformException {
        String exe;
        try {
            exe = ui.getExecutable(launcher);
        } catch (RuntimeException re) {
            throw new PerformException(re.getMessage());
        }
        
        Map<String,String> buildParameters = new HashMap<String, String>();
        String moduleRootRemote = workspace.getRemote();
        
        if (build instanceof AbstractBuild) {
           FilePath moduleRoot = ((AbstractBuild) build).getModuleRoot();
           moduleRootRemote = moduleRoot.getRemote();
           buildParameters = ((AbstractBuild) build).getBuildVariables();
        }
        
        return createCommandlineArgs(exe, moduleRootRemote, vars, buildParameters);
    }

    private Unity3dInstallation getAndConfigureUnity3dInstallation(FilePath workspace, TaskListener listener, EnvVars env) throws PerformException, IOException, InterruptedException {
        Unity3dInstallation ui = getUnity3dInstallation();

        if(ui==null) {
            throw new PerformException(Messages.Unity3d_NoUnity3dInstallation());
        }
        
        ui = ui.forNode(workspace.toComputer().getNode(), listener);
        ui = ui.forEnvironment(env);
        return ui;
    }

    ArgumentListBuilder createCommandlineArgs(String exe, String moduleRootRemote, EnvVars vars, Map<String,String> buildVariables) {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(exe);

        String theArgLine = getArgLineOrGlobalArgLine();

        String finalArgLine = Util.replaceMacro(theArgLine, buildVariables);
        finalArgLine = Util.replaceMacro(finalArgLine, vars);
        finalArgLine = Util.replaceMacro(finalArgLine, buildVariables);

        if (!finalArgLine.contains("-projectPath")) {
            args.add("-projectPath", moduleRootRemote);
        }

        args.add(QuotedStringTokenizer.tokenize(finalArgLine));
        return args;
    }

    /**
     * @return the Unity3d to invoke,
     * or null to invoke the default one.
     */
    private Unity3dInstallation getUnity3dInstallation() {
        for( Unity3dInstallation i : getDescriptor().getInstallations() ) {
            if(unity3dName!=null && unity3dName.equals(i.getName()))
                return i;
        }
        return null;
    }

    static Set<Integer> toIntegerSet(String csvIntegers) {
        Set<Integer> result = new HashSet<>();
        if (! csvIntegers.trim().equals("")) {
            result.addAll(Collections2.transform(Arrays.asList(csvIntegers.split(",")), new Function<String, Integer>() {
                @Override
                public Integer apply(String s) {
                    return Integer.parseInt(s.trim());
                }
            }));
        }
        return result;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private String globalArgLine;

        @CopyOnWrite
        private volatile Unity3dInstallation[] installations;

        public DescriptorImpl() {
            this.installations = new Unity3dInstallation[0];
            load();
        }

        public Unity3dInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(Unity3dInstallation.DescriptorImpl.class);
        }

        public Unity3dInstallation[] getInstallations() {
            return installations;
        }

        public void setInstallations(Unity3dInstallation... antInstallations) {
            this.installations = antInstallations;
            save();
        }

        public FormValidation doCheckUnstableReturnCodes(@QueryParameter String value) {
            try {
                toIntegerSet(value);
                return FormValidation.ok();
            } catch (RuntimeException re) {
                return FormValidation.error(Messages.Unity3d_InvalidParamUnstableReturnCodes(value));
            }
        }

        public String getGlobalArgLine() {
            return globalArgLine;
        }

        public void setGlobalArgLine(String globalArgLine) {
            //log.info("setGlobalArgLine: " + globalArgLine);
            this.globalArgLine = globalArgLine;
            save();
        }

        @Override
        public boolean configure( StaplerRequest req, JSONObject o ) {
            globalArgLine = Util.fixEmptyAndTrim(o.getString("globalArgLine"));
            save();

            return true;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Invoke Unity3d Editor";
        }
        
       public BuildStepMonitor getRequiredMonitorService() {
         return BuildStepMonitor.NONE;
        }
    }
}

