package com.workflowplatform.contracts.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FieldValidationErrorTests {

    private static final String INVALID = "invalid";
    private static final String NAME = "name";

    @Test
    void acceptsSafeStructuredFieldErrors() {
        FieldValidationError error =
                new FieldValidationError(
                        "items[0].displayName", "invalid_format", "format is invalid");

        assertEquals("items[0].displayName", error.field(), "Field path must be preserved");
        assertEquals("invalid_format", error.code(), "Field code must be preserved");
        assertEquals("format is invalid", error.message(), "Safe message must be preserved");
        assertEquals(
                "_request",
                new FieldValidationError("_request", INVALID, "request is invalid").field(),
                "Request-level validation must use the reserved field");
    }

    @Test
    void rejectsUnsafeOrUnboundedFieldErrors() {
        assertThrows(
                NullPointerException.class,
                () -> new FieldValidationError(null, INVALID, INVALID),
                "Null field paths must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FieldValidationError("items[-1]", INVALID, INVALID),
                "Negative indexes must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FieldValidationError(NAME, "INVALID", INVALID),
                "Uppercase field codes must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FieldValidationError(NAME, INVALID, " "),
                "Blank messages must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FieldValidationError("a".repeat(201), INVALID, INVALID),
                "Oversized field paths must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FieldValidationError(NAME, "a".repeat(65), INVALID),
                "Oversized field codes must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new FieldValidationError(NAME, INVALID, "a".repeat(301)),
                "Oversized messages must be rejected");
    }
}
