package com.workflowplatform.controlplane.definition;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** One deterministic configuration failure at a bounded JSON Pointer. */
public record WorkflowConfigurationViolation(
        WorkflowConfigurationValidationFailure failure, String path) implements Serializable {

    @Serial private static final long serialVersionUID = 1L;
    private static final int MAXIMUM_PATH_LENGTH = 160;

    public WorkflowConfigurationViolation {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.length() > MAXIMUM_PATH_LENGTH) {
            throw new IllegalArgumentException("Violation path must be a bounded JSON Pointer");
        }
    }
}
