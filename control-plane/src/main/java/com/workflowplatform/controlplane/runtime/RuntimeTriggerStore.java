package com.workflowplatform.controlplane.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;

/** Transactional persistence port for manual and HTTP workflow triggers. */
@FunctionalInterface
public interface RuntimeTriggerStore {

    RuntimeTriggerResult trigger(RuntimeTriggerCommand command);

    record RuntimeTriggerCommand(
            WorkflowRequestContext context,
            UUID workflowId,
            String triggerType,
            JsonNode input,
            String businessKey,
            String traceId,
            UUID idempotencyRecordId,
            String idempotencyScope,
            String keyDigest,
            String requestDigest,
            String inputReference,
            String inputDigest) {

        public RuntimeTriggerCommand {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(workflowId, "workflowId");
            triggerType = Objects.requireNonNull(triggerType, "triggerType");
            Objects.requireNonNull(input, "input");
            traceId = Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(idempotencyRecordId, "idempotencyRecordId");
            idempotencyScope = Objects.requireNonNull(idempotencyScope, "idempotencyScope");
            keyDigest = requireDigest(keyDigest, "keyDigest");
            requestDigest = requireDigest(requestDigest, "requestDigest");
            if (inputReference != null && inputReference.isBlank()) {
                throw new IllegalArgumentException("inputReference must not be blank");
            }
            inputDigest = requireDigest(inputDigest, "inputDigest");
        }

        private static String requireDigest(String value, String name) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
            }
            return value;
        }
    }
}
