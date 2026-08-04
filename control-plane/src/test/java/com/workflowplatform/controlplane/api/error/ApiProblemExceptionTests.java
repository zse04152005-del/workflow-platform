package com.workflowplatform.controlplane.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workflowplatform.contracts.problem.ProblemCode;
import org.junit.jupiter.api.Test;

class ApiProblemExceptionTests {

    @Test
    void onlyUsesStaticCatalogDetails() {
        ApiProblemException exception = ApiProblemException.of(ProblemCode.COMMON_CONFLICT_STATE);

        assertEquals(
                ProblemCode.COMMON_CONFLICT_STATE,
                exception.problemCode(),
                "Exception must retain its stable code");
        assertEquals(
                ProblemCode.COMMON_CONFLICT_STATE.descriptor().defaultDetail(),
                exception.getMessage(),
                "Exception message must come from the static catalog");
        assertNull(exception.retryAfterSeconds(), "Non-retryable error must omit Retry-After");
    }

    @Test
    void validatesRetryAfterSemantics() {
        ApiProblemException exception =
                ApiProblemException.retryAfter(ProblemCode.COMMON_RATE_LIMIT_EXCEEDED, 60);

        assertEquals(60, exception.retryAfterSeconds(), "Retry delay must be preserved");
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiProblemException.retryAfter(ProblemCode.COMMON_CONFLICT_STATE, 60),
                "Non-retryable errors must reject Retry-After");
        assertThrows(
                IllegalArgumentException.class,
                () -> ApiProblemException.retryAfter(ProblemCode.COMMON_RATE_LIMIT_EXCEEDED, 0),
                "Zero delay must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ApiProblemException.retryAfter(
                                ProblemCode.CONNECTOR_DEPENDENCY_UNAVAILABLE, 86_401),
                "Oversized delay must be rejected");
        assertThrows(
                NullPointerException.class,
                () -> ApiProblemException.retryAfter(null, 60),
                "Null problem codes must be rejected");
    }

    @Test
    void retainsInternalCausesWithoutChangingThePublicCatalogDetail() {
        IllegalArgumentException cause = new IllegalArgumentException("sensitive-internal-value");

        ApiProblemException exception =
                ApiProblemException.causedBy(ProblemCode.COMMON_VALIDATION_INVALID_FIELD, cause);

        assertSame(cause, exception.getCause(), "The internal cause must remain available to logs");
        assertEquals(
                ProblemCode.COMMON_VALIDATION_INVALID_FIELD.descriptor().defaultDetail(),
                exception.getMessage(),
                "The public-safe static detail must remain the exception message");
    }
}
