package com.workflowplatform.contracts.dsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable root value model for workflow DSL schema version 1. */
public record WorkflowDefinition(
        int schemaVersion,
        String name,
        String description,
        int catalogVersion,
        List<WorkflowTrigger> triggers,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        WorkflowParameters parameters,
        Map<String, Object> metadata) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkflowDefinition {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        name = DslModelSupport.requireBusinessName(name);
        description = DslModelSupport.optionalText(description, "description", 1000);
        catalogVersion = DslModelSupport.requireAtLeast(catalogVersion, 1, "catalogVersion");
        triggers = DslModelSupport.immutableList(triggers, 1, 20, "triggers");
        nodes = DslModelSupport.immutableList(nodes, 2, 500, "nodes");
        edges = DslModelSupport.immutableList(edges, 1, 2000, "edges");
        Objects.requireNonNull(parameters, "parameters");
        metadata = DslModelSupport.immutableMetadata(metadata);
    }

    @Override
    public List<WorkflowTrigger> triggers() {
        return Collections.unmodifiableList(new ArrayList<>(triggers));
    }

    @Override
    public List<WorkflowNode> nodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    @Override
    public List<WorkflowEdge> edges() {
        return Collections.unmodifiableList(new ArrayList<>(edges));
    }

    @Override
    public Map<String, Object> metadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
