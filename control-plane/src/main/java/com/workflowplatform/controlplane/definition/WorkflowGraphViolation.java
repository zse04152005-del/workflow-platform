package com.workflowplatform.controlplane.definition;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** One deterministic graph violation addressed by a JSON Pointer into the DSL document. */
public record WorkflowGraphViolation(WorkflowGraphValidationFailure failure, String path)
        implements Serializable {

    @Serial private static final long serialVersionUID = 1L;
    private static final int MAXIMUM_PATH_LENGTH = 128;

    public WorkflowGraphViolation {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(path, "path");
        if (path.isBlank() || path.charAt(0) != '/' || path.length() > MAXIMUM_PATH_LENGTH) {
            throw new IllegalArgumentException("path must be a bounded JSON Pointer");
        }
    }
}
