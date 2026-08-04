package com.workflowplatform.controlplane.definition;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** One deterministic expression failure addressed only by a bounded JSON Pointer. */
public record WorkflowExpressionViolation(WorkflowExpressionCompilationFailure failure, String path)
        implements Serializable {

    @Serial private static final long serialVersionUID = 1L;
    private static final int MAXIMUM_PATH_LENGTH = 256;

    public WorkflowExpressionViolation {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.length() > MAXIMUM_PATH_LENGTH) {
            throw new IllegalArgumentException("Expression path must be a bounded JSON Pointer");
        }
    }
}
