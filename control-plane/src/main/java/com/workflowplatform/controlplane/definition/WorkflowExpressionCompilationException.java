package com.workflowplatform.controlplane.definition;

import java.util.List;

/** Payload-safe rejection containing stable expression categories and JSON Pointers only. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class WorkflowExpressionCompilationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<WorkflowExpressionViolation> violations;

    public WorkflowExpressionCompilationException(List<WorkflowExpressionViolation> violations) {
        super(message(violations));
        this.violations = List.copyOf(violations);
    }

    public List<WorkflowExpressionViolation> violations() {
        return violations;
    }

    private static String message(List<WorkflowExpressionViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("Expression violations must not be empty");
        }
        return "Workflow expressions rejected: expression_invalid (" + violations.size() + ")";
    }
}
