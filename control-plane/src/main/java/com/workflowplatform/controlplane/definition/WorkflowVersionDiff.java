package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded deterministic JSON-Pointer differences between two immutable versions. */
public record WorkflowVersionDiff(
        UUID workflowId,
        VersionReference from,
        VersionReference to,
        int changeCount,
        boolean truncated,
        List<Change> changes) {

    public WorkflowVersionDiff {
        changes = List.copyOf(changes);
    }

    public record VersionReference(int version, String checksum, Instant publishedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(String operation, String path, JsonNode before, JsonNode after) {}
}
