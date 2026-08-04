package com.workflowplatform.worker;

/** Bounded node-plugin SPI used by the independent Worker process. */
public interface WorkerNodeExecutor {

    boolean supports(String nodeType);

    WorkerTaskStore.NodeExecutionResult execute(WorkerTaskStore.ClaimedTask task);
}
