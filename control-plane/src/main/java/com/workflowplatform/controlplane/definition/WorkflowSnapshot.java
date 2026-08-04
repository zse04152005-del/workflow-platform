package com.workflowplatform.controlplane.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable tenant-visible draft or published-version snapshot used by portability operations. */
public record WorkflowSnapshot(
        UUID workflowId,
        UUID projectId,
        String name,
        String description,
        String sourceKind,
        Long draftRevision,
        Integer version,
        String serializedDefinition,
        String checksum,
        Instant capturedAt) {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED_VERSION = "published_version";

    public WorkflowSnapshot {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(projectId, "projectId");
        name = Objects.requireNonNull(name, "name");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        serializedDefinition = Objects.requireNonNull(serializedDefinition, "serializedDefinition");
        checksum = WorkflowDraftValidation.requireDigest(checksum, "checksum");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (DRAFT.equals(sourceKind)) {
            if (draftRevision == null || draftRevision < 1 || version != null) {
                throw new IllegalArgumentException("Draft snapshot selector is invalid");
            }
        } else if (PUBLISHED_VERSION.equals(sourceKind)) {
            if (version == null || version < 1 || draftRevision != null) {
                throw new IllegalArgumentException("Published snapshot selector is invalid");
            }
        } else {
            throw new IllegalArgumentException("Unsupported workflow snapshot source");
        }
    }
}
