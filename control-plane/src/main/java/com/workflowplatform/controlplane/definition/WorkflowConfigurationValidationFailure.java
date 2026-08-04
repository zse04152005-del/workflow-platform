package com.workflowplatform.controlplane.definition;

/** Stable, payload-safe G3-04 failure categories. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public enum WorkflowConfigurationValidationFailure {
    TRIGGER_SCHEMA_INVALID("trigger_schema_invalid"),
    NODE_SCHEMA_INVALID("node_schema_invalid"),
    PARAMETER_TYPE_INVALID("parameter_type_invalid"),
    CONNECTOR_REFERENCE_INVALID("connector_reference_invalid"),
    CONNECTOR_UNAVAILABLE("connector_unavailable"),
    CONNECTOR_USE_FORBIDDEN("connector_use_forbidden"),
    SECRET_REFERENCE_INVALID("secret_reference_invalid"),
    SECRET_UNAVAILABLE("secret_unavailable"),
    SECRET_USE_FORBIDDEN("secret_use_forbidden");

    private final String wireName;

    WorkflowConfigurationValidationFailure(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
