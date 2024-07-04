# Dynamic Label Assigner Plugin

The Dynamic Label Assigner Plugin is a Jenkins plugin designed to dynamically assign labels to Jenkins Pipeline jobs based on the Docker image used in the pipeline script. It intercepts the build queue and modifies the pipeline script to replace the Docker agent with a specific label, ensuring jobs are routed to appropriate nodes.

## Compilation and Installation

### Prerequisites

- Java Development Kit (JDK) 8 or higher
- Apache Maven 3.6.0 or higher
- Jenkins 2.176.1 or higher

### Steps to Compile and Install

1. **Clone the repository:**

    ```sh
    git clone https://github.com/yourusername/dynamic-label-assigner.git
    cd dynamic-label-assigner
    ```

2. **Compile the project using Maven:**

    ```sh
    mvn clean install
    ```

3. **Install the plugin in Jenkins:**

    - Once the build is successful, a `.hpi` file will be generated in the `target` directory.
    - Upload this `.hpi` file to your Jenkins instance by navigating to `Manage Jenkins` -> `Manage Plugins` -> `Advanced` -> `Upload Plugin`.

## Class Structure

### DynamicLabelQueueListener

This is the main class of the plugin, which extends `QueueListener` to intercept buildable items in the Jenkins queue.

#### Methods

- **onEnterBuildable(Queue.BuildableItem item)**
  
  Triggered when a job enters the buildable queue. Checks if the job is a pipeline job and processes it accordingly.

- **handlePipelineJob(Queue.BuildableItem item, WorkflowJob job)**
  
  Handles pipeline jobs by retrieving the original workflow run, computing new labels, and replaying the build with the modified script.

- **getWorkflowRunFromItem(Queue.BuildableItem item, TaskListener listener)**
  
  Retrieves the `WorkflowRun` instance from the `Queue.BuildableItem`.

- **isDeclarativePipeline(String script)**
  
  Checks if the given pipeline script is a declarative pipeline.

- **computeNewLabelFromScript(String pipelineScript, TaskListener listener)**
  
  Computes a new label based on the Docker image used in the pipeline script.

- **isUsingDockerAgent(String pipelineScript)**
  
  Checks if the pipeline script is using a Docker agent.

- **extractDockerImage(String pipelineScript)**
  
  Extracts the Docker image from the pipeline script.

- **modifyScriptWithLabel(String script, String newLabel, TaskListener listener)**
  
  Modifies the pipeline script to replace the Docker agent with the new label.

- **stopOriginalRun(WorkflowRun originalRun, TaskListener listener)**
  
  Stops the original build run.

- **replayBuildWithModifiedScript(WorkflowRun originalRun, String modifiedScript, Map<String, String> loadedScripts, TaskListener listener)**
  
  Replays the build with the modified script and original loaded scripts.

- **log(TaskListener listener, String message)**
  
  Logs a message with a default log level of `Level.INFO`.

- **log(TaskListener listener, String message, Level level)**
  
  Logs a message with a specified log level.

## Logging

The plugin uses the `java.util.logging.Logger` for logging. By default, the log level is set to `INFO`. You can configure the log level in Jenkins' global settings.


