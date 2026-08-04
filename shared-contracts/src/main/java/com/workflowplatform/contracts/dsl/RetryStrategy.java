package com.workflowplatform.contracts.dsl;

/** Stable retry strategies in workflow DSL schema version 1. */
public enum RetryStrategy {
    FIXED("fixed"),
    LINEAR("linear"),
    EXPONENTIAL("exponential");

    private final String value;

    RetryStrategy(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
