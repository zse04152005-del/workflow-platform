package com.workflowplatform.controlplane.definition;

import java.util.List;

/** Payload-safe rejection containing only stable categories and JSON Pointers. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class WorkflowConfigurationValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<WorkflowConfigurationViolation> violations;

    public WorkflowConfigurationValidationException(
            List<WorkflowConfigurationViolation> violations) {
        super(message(violations));
        this.violations = List.copyOf(violations);
    }

    public List<WorkflowConfigurationViolation> violations() {
        return violations;
    }

    private static String message(List<WorkflowConfigurationViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("Configuration violations must not be empty");
        }
        return "Workflow configuration rejected: configuration_invalid (" + violations.size() + ")";
    }
}
