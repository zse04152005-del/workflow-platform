package com.workflowplatform.contracts.problem;

import java.util.Objects;
import java.util.regex.Pattern;

/** Safe field-level validation item; rejected values are intentionally absent. */
public record FieldValidationError(String field, String code, String message) {

    private static final Pattern FIELD_PATH =
            Pattern.compile(
                    "^(?:_request|[A-Za-z][A-Za-z0-9_]*(?:\\[(?:0|[1-9][0-9]*)\\]|\\.[A-Za-z][A-Za-z0-9_]*)*)$");
    private static final Pattern FIELD_CODE = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$");

    public FieldValidationError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (field.length() > 200 || !FIELD_PATH.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid field path");
        }
        if (code.length() > 64 || !FIELD_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid field validation code");
        }
        if (message.isBlank() || message.length() > 300) {
            throw new IllegalArgumentException("Invalid field validation message");
        }
    }
}
