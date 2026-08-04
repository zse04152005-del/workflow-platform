package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** Stable, payload-safe public validation issue. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowValidationIssue(
        String code, String path, String nodeId, String message, String severity) {

    private static final int MAXIMUM_CODE_LENGTH = 96;
    private static final int MAXIMUM_PATH_LENGTH = 256;
    private static final int MAXIMUM_NODE_ID_LENGTH = 80;
    private static final int MAXIMUM_MESSAGE_LENGTH = 160;

    public WorkflowValidationIssue {
        code = bounded(code, "code", MAXIMUM_CODE_LENGTH);
        path = bounded(path, "path", MAXIMUM_PATH_LENGTH);
        message = bounded(message, "message", MAXIMUM_MESSAGE_LENGTH);
        severity = Objects.requireNonNull(severity, "severity");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must be a JSON Pointer");
        }
        if (nodeId != null && (nodeId.isBlank() || nodeId.length() > MAXIMUM_NODE_ID_LENGTH)) {
            throw new IllegalArgumentException("nodeId must be bounded when present");
        }
        if (!"error".equals(severity)) {
            throw new IllegalArgumentException("Only error severity is public in G3-07");
        }
    }

    private static String bounded(String value, String label, int maximum) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must be bounded and non-blank");
        }
        return value;
    }
}
