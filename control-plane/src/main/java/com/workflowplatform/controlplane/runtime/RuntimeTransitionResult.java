package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Committed transition identity returned only after state, audit and Outbox are durable. */
public record RuntimeTransitionResult(
        RuntimeEntityType entityType,
        UUID entityId,
        String command,
        String previousStatus,
        String currentStatus,
        long version,
        UUID eventId,
        Instant occurredAt) {

    public RuntimeTransitionResult {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(currentStatus, "currentStatus");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (version <= 0) {
            throw new IllegalArgumentException("committed version must be positive");
        }
    }
}
