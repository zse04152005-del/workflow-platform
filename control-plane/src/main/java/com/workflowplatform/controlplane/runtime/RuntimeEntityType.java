package com.workflowplatform.controlplane.runtime;

/** Runtime aggregate types governed by the persisted state-transition component. */
public enum RuntimeEntityType {
    INSTANCE("instance", "workflow_instance"),
    NODE("node", "node_instance"),
    ATTEMPT("attempt", "task_execution"),
    SCHEDULE("schedule", "schedule_occurrence");

    private final String wireNameValue;
    private final String tableNameValue;

    RuntimeEntityType(String wireName, String tableName) {
        this.wireNameValue = wireName;
        this.tableNameValue = tableName;
    }

    public String wireName() {
        return wireNameValue;
    }

    public String tableName() {
        return tableNameValue;
    }
}
