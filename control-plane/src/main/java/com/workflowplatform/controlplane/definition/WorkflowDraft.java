package com.workflowplatform.controlplane.definition;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped persisted workflow draft representation. */
public record WorkflowDraft(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String status,
        long draftRevision,
        Integer publishedVersion,
        String canonicalDefinition,
        String draftChecksum,
        Instant createdAt,
        Instant updatedAt) {

    private static final long MINIMUM_DRAFT_REVISION = 1;

    public WorkflowDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        name = Objects.requireNonNull(name, "name");
        status = Objects.requireNonNull(status, "status");
        canonicalDefinition = Objects.requireNonNull(canonicalDefinition, "canonicalDefinition");
        draftChecksum = Objects.requireNonNull(draftChecksum, "draftChecksum");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (draftRevision < MINIMUM_DRAFT_REVISION) {
            throw new IllegalArgumentException("draftRevision must be positive");
        }
    }

    public String wireStatus() {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "DRAFT" -> "draft";
            case "ACTIVE" -> "active";
            case "DEACTIVATED" -> "inactive";
            default -> throw new IllegalStateException("Unsupported workflow status");
        };
    }
}
