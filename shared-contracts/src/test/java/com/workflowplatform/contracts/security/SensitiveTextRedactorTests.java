package com.workflowplatform.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class SensitiveTextRedactorTests {

    @Test
    void redactsCredentialShapesJwtAndControlsWithinTheBound() {
        String redacted =
                SensitiveTextRedactor.redact(
                        "password=very-secret\\nAuthorization: Bearer abc.def.ghi "
                                + "token='other-secret' "
                                + "eyJabcdefghijk.abcdefghijk.abcdefghijk trailing",
                        120);

        assertFalse(redacted.contains("very-secret"));
        assertFalse(redacted.contains("other-secret"));
        assertFalse(redacted.contains("eyJabcdefghijk"));
        assertFalse(redacted.contains("\\n"));
        assertEquals(redacted, SensitiveTextRedactor.redact(redacted, 120));
    }

    @Test
    void handlesBlankBoundsAndTruncationDeterministically() {
        assertEquals("<redacted>", SensitiveTextRedactor.redact(" ", 20));
        assertEquals("0123456789", SensitiveTextRedactor.redact("0123456789abcdef", 10));
        assertThrows(
                IllegalArgumentException.class, () -> SensitiveTextRedactor.redact("value", 3));
    }
}
