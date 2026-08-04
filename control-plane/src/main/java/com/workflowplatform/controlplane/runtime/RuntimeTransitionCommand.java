package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Complete optimistic-lock command for one runtime state transition. */
public record RuntimeTransitionCommand(
        WorkflowRequestContext context,
        RuntimeEntityType entityType,
        UUID entityId,
        String command,
        String expectedStatus,
        long expectedVersion,
        String targetStatus,
        RuntimeTransitionActor actor,
        String reasonCode,
        String traceId,
        RuntimeTransitionEffects effects) {

    private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern COMMAND = Pattern.compile("[a-z][a-z0-9_]{0,119}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,119}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";

    public RuntimeTransitionCommand {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(effects, "effects");
        command = requireMatch(command, COMMAND, "command");
        expectedStatus = requireMatch(expectedStatus, STATUS, "expectedStatus");
        targetStatus = requireMatch(targetStatus, STATUS, "targetStatus");
        reasonCode = requireMatch(reasonCode, REASON, "reasonCode");
        traceId = requireMatch(traceId, TRACE_ID, "traceId");
        if (ZERO_TRACE_ID.equals(traceId)) {
            throw new IllegalArgumentException("traceId cannot be all zeroes");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }
    }

    private static String requireMatch(String value, Pattern pattern, String label) {
        String required = Objects.requireNonNull(value, label);
        if (!pattern.matcher(required).matches()) {
            throw new IllegalArgumentException(label + " has an invalid stable-contract format");
        }
        return required;
    }
}
