package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import java.util.List;
import java.util.Objects;

/** Immutable graph validation result and deterministic compiler traversal order. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class ValidatedWorkflowGraph {

    private final WorkflowDefinition definition;
    private final String startNodeId;
    private final List<String> endNodeIds;
    private final List<String> topologicalNodeIds;

    ValidatedWorkflowGraph(
            WorkflowDefinition definition,
            String startNodeId,
            List<String> endNodeIds,
            List<String> topologicalNodeIds) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.startNodeId = Objects.requireNonNull(startNodeId, "startNodeId");
        this.endNodeIds = List.copyOf(endNodeIds);
        this.topologicalNodeIds = List.copyOf(topologicalNodeIds);
    }

    public WorkflowDefinition definition() {
        return definition;
    }

    public String startNodeId() {
        return startNodeId;
    }

    public List<String> endNodeIds() {
        return endNodeIds;
    }

    public List<String> topologicalNodeIds() {
        return topologicalNodeIds;
    }
}
