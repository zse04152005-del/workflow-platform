package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable instance snapshot captured at trigger time and bound to one workflow version. */
public record RuntimeInstanceSnapshot(
        UUID instanceId,
        UUID workflowId,
        UUID workflowVersionId,
        int workflowVersion,
        String inputDigest,
        String businessKey,
        String actorId,
        String traceId,
        Instant createdAt) {

    public RuntimeInstanceSnapshot {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId");
        inputDigest = requireDigest(inputDigest, "inputDigest");
        Objects.requireNonNull(actorId, "actorId");
        if (workflowVersion < 1 || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid workflow snapshot binding");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
