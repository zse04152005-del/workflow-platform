package com.workflowplatform.worker;

import java.util.Optional;

/** Connector hook for resolving an ambiguous failure before the Worker schedules a retry. */
public interface ExternalResultQueryHook {

    boolean supports(String nodeType);

    Optional<WorkerTaskStore.NodeExecutionResult> query(
            WorkerTaskStore.ClaimedTask task, WorkerTaskStore.NodeExecutionResult ambiguousFailure);
}
