package com.workflowplatform.contracts.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProblemDescriptorTests {

    private static final String TEST_CODE = "COMMON_INTERNAL_TEST";
    private static final String TITLE = "Title";
    private static final String DETAIL = "Detail";

    @Test
    void exposesTheFrozenStandardCatalog() {
        Set<String> codes =
                Arrays.stream(ProblemCode.values()).map(Enum::name).collect(Collectors.toSet());

        assertEquals(13, codes.size(), "The reviewed catalog size must remain stable");
        assertTrue(
                codes.contains("COMMON_VALIDATION_INVALID_FIELD"),
                "Validation baseline code must exist");
        assertTrue(
                codes.contains("COMMON_INTERNAL_UNEXPECTED"), "Internal fallback code must exist");
        assertEquals(
                URI.create("https://api.workflow-platform.example/problems/rate-limit-exceeded"),
                ProblemCode.COMMON_RATE_LIMIT_EXCEEDED.descriptor().type(),
                "Rate-limit problem type must remain stable");
        assertEquals(
                429,
                ProblemCode.COMMON_RATE_LIMIT_EXCEEDED.descriptor().status(),
                "Rate-limit status must remain 429");
        assertTrue(
                ProblemCode.COMMON_RATE_LIMIT_EXCEEDED.descriptor().retryable(),
                "Rate-limit failures must be retryable");
        assertFalse(
                ProblemCode.COMMON_CONFLICT_STATE.descriptor().retryable(),
                "State conflicts must not be marked retryable");
        assertEquals(
                412,
                ProblemCode.DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH.descriptor().status(),
                "ETag mismatches must use HTTP 412");
        assertEquals(
                428,
                ProblemCode.DEFINITION_PRECONDITION_REQUIRED_IF_MATCH.descriptor().status(),
                "Missing If-Match must use HTTP 428");
    }

    @Test
    void rejectsInvalidProblemMetadata() {
        URI validType = URI.create("https://api.workflow-platform.example/problems/test");

        assertThrows(
                NullPointerException.class,
                () -> new ProblemDescriptor(null, validType, TITLE, 400, DETAIL, false),
                "Null error codes must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProblemDescriptor("bad-code", validType, TITLE, 400, DETAIL, false),
                "Malformed stable error codes must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProblemDescriptor(
                                TEST_CODE,
                                URI.create("http://example.test/problem"),
                                TITLE,
                                500,
                                DETAIL,
                                false),
                "Non-HTTPS problem types must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProblemDescriptor(TEST_CODE, validType, " ", 500, DETAIL, false),
                "Blank titles must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProblemDescriptor(TEST_CODE, validType, TITLE, 399, DETAIL, false),
                "Non-error status codes must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProblemDescriptor(TEST_CODE, validType, TITLE, 600, DETAIL, false),
                "Out-of-range status codes must be rejected");
    }
}
