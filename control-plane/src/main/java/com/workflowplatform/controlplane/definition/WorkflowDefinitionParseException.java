package com.workflowplatform.controlplane.definition;

import java.io.Serial;
import java.util.Objects;

/** Payload-safe parser failure with an optional one-based source location. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class WorkflowDefinitionParseException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final WorkflowDefinitionParseFailure failure;
    private final int line;
    private final int column;

    WorkflowDefinitionParseException(
            WorkflowDefinitionParseFailure failure, int line, int column, Throwable cause) {
        super(messageFor(failure), cause);
        this.failure = Objects.requireNonNull(failure, "failure");
        this.line = requireNonNegative(line, "line");
        this.column = requireNonNegative(column, "column");
    }

    public WorkflowDefinitionParseFailure failure() {
        return failure;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    private static String messageFor(WorkflowDefinitionParseFailure failure) {
        return "Workflow definition source rejected: "
                + Objects.requireNonNull(failure, "failure").wireName();
    }

    private static int requireNonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must not be negative");
        }
        return value;
    }
}
