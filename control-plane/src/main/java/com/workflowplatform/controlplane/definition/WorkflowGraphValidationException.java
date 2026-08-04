package com.workflowplatform.controlplane.definition;

import java.io.Serial;
import java.util.List;

/** Payload-safe aggregate failure raised when graph invariants are violated. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class WorkflowGraphValidationException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final List<WorkflowGraphViolation> violations;

    WorkflowGraphValidationException(List<WorkflowGraphViolation> violations) {
        super(messageFor(violations));
        this.violations = List.copyOf(violations);
    }

    public List<WorkflowGraphViolation> violations() {
        return violations;
    }

    private static String messageFor(List<WorkflowGraphViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        return "Workflow graph rejected: graph_invalid (" + violations.size() + ")";
    }
}
