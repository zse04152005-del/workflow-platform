package com.workflowplatform.controlplane.definition;

/** Stable, payload-safe categories returned when an expression cannot be published. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public enum WorkflowExpressionCompilationFailure {
    SYNTAX_INVALID("expression_syntax_invalid"),
    TYPE_MISMATCH("expression_type_mismatch"),
    IDENTIFIER_UNKNOWN("expression_identifier_unknown"),
    OPERATION_FORBIDDEN("expression_operation_forbidden"),
    BUDGET_EXCEEDED("expression_budget_exceeded");

    private final String wireName;

    WorkflowExpressionCompilationFailure(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
