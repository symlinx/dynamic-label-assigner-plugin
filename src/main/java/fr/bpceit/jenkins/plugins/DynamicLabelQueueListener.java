package fr.bpceit.jenkins.plugins;

import hudson.Extension;
import hudson.model.Cause;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.QueueListener;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution.PlaceholderTask;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class DynamicLabelQueueListener extends QueueListener {

    private static final Logger LOGGER = Logger.getLogger(DynamicLabelQueueListener.class.getName());
    private static final ThreadLocal<Boolean> bypassListener = ThreadLocal.withInitial(() -> false);

    @Override
    public void onEnterBuildable(Queue.BuildableItem item) {
        if (bypassListener.get()) {
            log(null, "Bypassing listener for item: " + item.task.getName(), Level.INFO);
            return;
        }

        log(null, "Triggered for item: " + item.task.getName(), Level.INFO);
        log(null, "Task class: " + item.task.getClass().getName(), Level.INFO);

        // Log and list all build causes
        List<Cause> causes = item.getCauses();
        for (Cause cause : causes) {
            log(null, "Build cause: " + cause.getClass().getName() + " - " + cause.getShortDescription(), Level.INFO);
        }

        if (item.task instanceof PlaceholderTask) {
            PlaceholderTask placeholderTask = (PlaceholderTask) item.task;
            WorkflowRun run = (WorkflowRun) placeholderTask.run();
            if (run != null) {
                WorkflowJob job = run.getParent();
                log(null, "Handling pipeline job: " + job.getName(), Level.INFO);
                handlePipelineJob(item, job);
            } else {
                log(null, "Failed to retrieve WorkflowRun from PlaceholderTask: " + item.task.getName(), Level.WARNING);
            }
        } else {
            log(null, "Not a PlaceholderTask: " + item.task.getClass().getName(), Level.INFO);
        }
    }

    private void handlePipelineJob(Queue.BuildableItem item, WorkflowJob job) {
        log(null, "Inside handlePipelineJob for job: " + job.getName(), Level.INFO);
        try {
            TaskListener listener = StreamTaskListener.fromStdout();

            WorkflowRun originalRun = getWorkflowRunFromItem(item, listener);
            if (originalRun == null) {
                log(listener, "Unable to retrieve the original WorkflowRun.", Level.SEVERE);
                return;
            }

            log(listener, "Retrieved original run: " + originalRun.getId(), Level.INFO);
            ReplayAction replayAction = originalRun.getAction(ReplayAction.class);
            if (replayAction == null) {
                log(listener, "ReplayAction not found for the run.", Level.SEVERE);
                return;
            }

            log(listener, "Retrieved ReplayAction for the run.", Level.INFO);
            String pipelineScript = replayAction.getOriginalScript();
            Map<String, String> loadedScripts = replayAction.getOriginalLoadedScripts();

            boolean hasDockerAgent = false;

            if (pipelineScript != null || loadedScripts != null) {
                log(listener, "Retrieved pipeline and/or loaded scripts.", Level.INFO);

                boolean modified = false;

                if (pipelineScript != null) {
                    log(listener, "Retrieved pipeline script: " + pipelineScript, Level.INFO);
                    hasDockerAgent = hasDockerAgent(pipelineScript);
                    if (hasDockerAgent) {
                        pipelineScript = modifyAllDockerAgents(pipelineScript, listener);
                        modified = true;
                    }
                }

                if (loadedScripts != null) {
                    for (Map.Entry<String, String> entry : loadedScripts.entrySet()) {
                        String scriptName = entry.getKey();
                        String scriptContent = entry.getValue();

                        log(listener, "Processing loaded script: " + scriptName, Level.INFO);
                        if (hasDockerAgent(scriptContent)) {
                            hasDockerAgent = true;
                            String modifiedScriptContent = modifyAllDockerAgents(scriptContent, listener);
                            if (!scriptContent.equals(modifiedScriptContent)) {
                                loadedScripts.put(scriptName, modifiedScriptContent);
                                modified = true;
                            }
                        }
                    }
                }

                if (hasDockerAgent && modified) {
                    boolean replaySuccess = replayBuildWithModifiedScript(originalRun, pipelineScript, loadedScripts, listener);
                    if (replaySuccess) {
                        stopOriginalRun(originalRun, listener);
                    }
                } else {
                    log(listener, "No Docker agent found or no matching label. Running the script unmodified.", Level.INFO);
                }
            } else {
                log(listener, "No pipeline script found.", Level.SEVERE);
            }
        } catch (Exception e) {
            log(null, "Exception in handlePipelineJob: " + e.getMessage(), Level.SEVERE);
            e.printStackTrace();
        }
    }

    private String modifyAllDockerAgents(String script, TaskListener listener) {
        log(listener, "Modifying all Docker agents in the script.", Level.INFO);
        StringBuilder modifiedScript = new StringBuilder();
        Matcher matcher = Pattern.compile("agent\\s*\\{\\s*docker\\s*\\{[^}]*image\\s+['\"]([^'\"]+)['\"].*?\\}\\s*\\}", Pattern.DOTALL).matcher(script);
        int lastEnd = 0;
        while (matcher.find()) {
            modifiedScript.append(script, lastEnd, matcher.start());
            String dockerImage = matcher.group(1);
            String newLabel = "GFS_" + dockerImage.substring(dockerImage.lastIndexOf('/') + 1).replace(':', '_');
            String modifiedAgent = matcher.group().replaceAll("docker\\s*\\{[^}]*\\}", "label '" + newLabel + "'");
            modifiedScript.append(modifiedAgent);
            lastEnd = matcher.end();
        }
        modifiedScript.append(script.substring(lastEnd));
        log(listener, "Finished modifying Docker agents in the script.", Level.INFO);
        return modifiedScript.toString();
    }

    private boolean hasDockerAgent(String script) {
        Pattern pattern = Pattern.compile("agent\\s*\\{\\s*docker\\s*\\{", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(script);
        return matcher.find();
    }

    private WorkflowRun getWorkflowRunFromItem(Queue.BuildableItem item, TaskListener listener) {
        log(listener, "Inside getWorkflowRunFromItem", Level.INFO);
        try {
            log(listener, "Attempting to retrieve executable...", Level.INFO);
            if (item.task instanceof PlaceholderTask) {
                PlaceholderTask placeholderTask = (PlaceholderTask) item.task;
                WorkflowRun run = (WorkflowRun) placeholderTask.run();
                if (run != null) {
                    log(listener, "Retrieved WorkflowRun from PlaceholderTask.", Level.INFO);
                    return run;
                } else {
                    log(listener, "PlaceholderTask.run() returned null.", Level.WARNING);
                }
            } else {
                log(listener, "Item task is not an instance of PlaceholderTask.", Level.WARNING);
            }
        } catch (Exception e) {
            log(listener, "Exception while getting the WorkflowRun: " + e.getMessage(), Level.SEVERE);
            e.printStackTrace();
        }
        return null;
    }

    private boolean isDeclarativePipeline(String script) {
        log(null, "Checking if the script is declarative.", Level.INFO);
        Pattern pattern = Pattern.compile("^pipeline \\{", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(script);
        return matcher.find();
    }

    private boolean isUsingDockerAgent(String pipelineScript) {
        log(null, "Checking if the script is using Docker agent.", Level.INFO);
        Pattern pattern = Pattern.compile("agent\\s*\\{\\s*docker\\s*\\{", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(pipelineScript);
        return matcher.find();
    }

    private String extractDockerImage(String pipelineScript) {
        log(null, "Extracting Docker image from the script.", Level.INFO);
        Pattern pattern = Pattern.compile("agent\\s*\\{\\s*docker\\s*\\{\\s*[^}]*image\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(pipelineScript);

        if (matcher.find()) {
            log(null, "Found Docker image: " + matcher.group(1), Level.INFO);
            return matcher.group(1);
        }
        log(null, "No Docker image found.", Level.INFO);
        return null;
    }

    private void stopOriginalRun(WorkflowRun originalRun, TaskListener listener) {
        log(listener, "Stopping original run: " + originalRun.getId(), Level.INFO);
        try {
            originalRun.doStop();
            log(listener, "Original build stopped successfully.", Level.INFO);
        } catch (Exception e) {
            log(listener, "Failed to stop the original build: " + e.getMessage(), Level.SEVERE);
            e.printStackTrace();
        }
    }

    private boolean replayBuildWithModifiedScript(WorkflowRun originalRun, String modifiedScript, Map<String, String> loadedScripts, TaskListener listener) {
        log(listener, "Replaying build with modified script.", Level.INFO);
        try {
            bypassListener.set(true);

            ReplayAction replayAction = originalRun.getAction(ReplayAction.class);
            if (replayAction != null) {
                replayAction.run2(modifiedScript, loadedScripts);
                log(listener, "Successfully replayed build with modified script.", Level.INFO);
                return true;
            } else {
                log(listener, "ReplayAction not found for the run.", Level.SEVERE);
                return false;
            }
        } catch (Exception e) {
            log(listener, "Failed to replay build with modified script: " + e.getMessage(), Level.SEVERE);
            e.printStackTrace();
            return false;
        } finally {
            bypassListener.remove();
        }
    }

    private void log(TaskListener listener, String message) {
        log(listener, message, Level.INFO);
    }

    private void log(TaskListener listener, String message, Level level) {
        if (listener != null) {
            listener.getLogger().println(message);
        } else {
            LOGGER.log(level, message);
        }
    }
}
