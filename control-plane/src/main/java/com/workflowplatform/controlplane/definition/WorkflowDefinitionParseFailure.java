package com.workflowplatform.controlplane.definition;

/** Stable, payload-safe reasons why a workflow definition source cannot be parsed. */
public enum WorkflowDefinitionParseFailure {
    EMPTY_INPUT("empty_input"),
    INPUT_TOO_LARGE("input_too_large"),
    INVALID_UTF8("invalid_utf8"),
    MALFORMED_DOCUMENT("malformed_document"),
    RESOURCE_LIMIT_EXCEEDED("resource_limit_exceeded"),
    MULTIPLE_DOCUMENTS("multiple_documents"),
    ROOT_NOT_OBJECT("root_not_object"),
    CORE_MODEL_INVALID("core_model_invalid"),
    CANONICAL_OUTPUT_TOO_LARGE("canonical_output_too_large");

    private final String value;

    WorkflowDefinitionParseFailure(String value) {
        this.value = value;
    }

    public String wireName() {
        return value;
    }
}
