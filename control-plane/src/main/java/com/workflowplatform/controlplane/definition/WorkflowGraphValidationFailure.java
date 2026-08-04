package com.workflowplatform.controlplane.definition;

/** Stable reasons why a workflow definition graph cannot enter compilation. */
public enum WorkflowGraphValidationFailure {
    START_NODE_COUNT_INVALID("start_node_count_invalid"),
    END_NODE_REQUIRED("end_node_required"),
    DUPLICATE_NODE_ID("duplicate_node_id"),
    DUPLICATE_EDGE_ID("duplicate_edge_id"),
    UNKNOWN_EDGE_SOURCE("unknown_edge_source"),
    UNKNOWN_EDGE_TARGET("unknown_edge_target"),
    SELF_LOOP_NOT_ALLOWED("self_loop_not_allowed"),
    INCOMING_EDGE_TO_START("incoming_edge_to_start"),
    OUTGOING_EDGE_FROM_END("outgoing_edge_from_end"),
    TRANSITION_NOT_ALLOWED("transition_not_allowed"),
    DUPLICATE_EDGE_ROUTE("duplicate_edge_route"),
    OUTGOING_TRANSITION_AMBIGUOUS("outgoing_transition_ambiguous"),
    OUTGOING_EDGE_COUNT_INVALID("outgoing_edge_count_invalid"),
    NODE_UNREACHABLE("node_unreachable"),
    NODE_CANNOT_REACH_END("node_cannot_reach_end"),
    CYCLE_NOT_ALLOWED("cycle_not_allowed");

    private final String value;

    WorkflowGraphValidationFailure(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
