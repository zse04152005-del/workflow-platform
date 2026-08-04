package com.workflowplatform.contracts.dsl;

/** Stable node discriminators in workflow DSL schema version 1. */
public enum NodeType {
    START("start"),
    END("end"),
    HTTP("http"),
    CONDITION("condition"),
    DELAY("delay"),
    HUMAN_TASK("human_task");

    private final String value;

    NodeType(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
