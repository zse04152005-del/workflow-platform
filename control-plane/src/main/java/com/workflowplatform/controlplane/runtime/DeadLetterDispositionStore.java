package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.UUID;

/** Transactional persistence port for dead-letter notes and governed redispatch. */
@FunctionalInterface
public interface DeadLetterDispositionStore {

    DeadLetterDispositionResult execute(DeadLetterDispositionCommand command);

    record DeadLetterDispositionCommand(
            WorkflowRequestContext context,
            UUID deadLetterId,
            DeadLetterAction action,
            String noteRedacted,
            String traceId,
            UUID idempotencyRecordId,
            UUID actionId,
            String operationScope,
            String keyDigest,
            String requestDigest) {}

    enum DeadLetterAction {
        NOTE("note"),
        REDISPATCH("redispatch");

        private final String operationName;

        DeadLetterAction(String value) {
            operationName = value;
        }

        public String wireName() {
            return operationName;
        }
    }
}
