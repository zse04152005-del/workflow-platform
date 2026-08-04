package com.workflowplatform.contracts.dsl;

/** Stable trigger discriminators in workflow DSL schema version 1. */
public enum TriggerType {
    MANUAL("manual"),
    HTTP("http"),
    CRON("cron");

    private final String value;

    TriggerType(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
