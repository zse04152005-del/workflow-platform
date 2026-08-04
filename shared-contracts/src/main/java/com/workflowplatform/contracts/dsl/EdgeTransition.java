package com.workflowplatform.contracts.dsl;

/** Stable edge transition discriminators in workflow DSL schema version 1. */
public enum EdgeTransition {
    SUCCESS("success"),
    FAILURE("failure"),
    ALWAYS("always"),
    CONDITION("condition");

    private final String value;

    EdgeTransition(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
