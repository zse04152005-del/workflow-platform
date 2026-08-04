package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable response returned by a trigger, including whether it was replayed. */
public record RuntimeTriggerResult(
        UUID instanceId,
        UUID workflowVersionId,
        int workflowVersion,
        String status,
        boolean replayed,
        int responseStatus,
        String requestDigest,
        Instant acceptedAt) {

    public RuntimeTriggerResult {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (workflowVersion < 1 || responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("invalid trigger response metadata");
        }
    }
}
