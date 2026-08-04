package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Versioned, integrity-bound, Secret-free workflow portability bundle. */
public record WorkflowExportBundle(
        String format,
        int formatVersion,
        Source source,
        Workflow workflow,
        List<ResourceReference> resources) {

    public static final String FORMAT = "workflow-platform-definition";
    public static final int FORMAT_VERSION = 1;

    public WorkflowExportBundle {
        if (resources != null) {
            resources = List.copyOf(resources);
        }
    }

    @Override
    public List<ResourceReference> resources() {
        return resources == null ? null : List.copyOf(resources);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(
            UUID workflowId,
            String kind,
            Long draftRevision,
            Integer version,
            String checksum,
            Instant capturedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Workflow(String name, String description, JsonNode definition) {}

    public record ResourceReference(String kind, String reference) {}
}
