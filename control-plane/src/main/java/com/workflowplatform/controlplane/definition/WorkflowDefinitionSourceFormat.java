package com.workflowplatform.controlplane.definition;

/** Explicit source formats accepted by the workflow definition parser. */
public enum WorkflowDefinitionSourceFormat {
    JSON("json"),
    YAML("yaml");

    private final String value;

    WorkflowDefinitionSourceFormat(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
