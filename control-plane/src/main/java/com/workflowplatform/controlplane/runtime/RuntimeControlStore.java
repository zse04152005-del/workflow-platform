package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.UUID;

/** Transactional persistence port for governed instance control and replay actions. */
@FunctionalInterface
public interface RuntimeControlStore {

    RuntimeControlResult execute(RuntimeControlCommand command);

    record RuntimeControlCommand(
            WorkflowRequestContext context,
            UUID instanceId,
            RuntimeControlAction action,
            String reason,
            String fromNodeId,
            String traceId,
            UUID idempotencyRecordId,
            String operationScope,
            String keyDigest,
            String requestDigest) {}

    enum RuntimeControlAction {
        PAUSE("pause"),
        RESUME("resume"),
        CANCEL("cancel"),
        REPLAY("replay");

        private final String operationName;

        RuntimeControlAction(String value) {
            operationName = value;
        }

        public String wireName() {
            return operationName;
        }
    }
}
