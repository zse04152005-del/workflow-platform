package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public immutable publication result bound to one persisted workflow version. */
public record PublishedWorkflow(
        UUID workflowId,
        int version,
        String checksum,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant publishedAt) {

    private static final int MINIMUM_VERSION = 1;

    public PublishedWorkflow {
        Objects.requireNonNull(workflowId, "workflowId");
        checksum = WorkflowDraftValidation.requireDigest(checksum, "checksum");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (version < MINIMUM_VERSION) {
            throw new IllegalArgumentException("version must be positive");
        }
    }
}
