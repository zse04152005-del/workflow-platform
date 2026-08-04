package com.workflowplatform.controlplane.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workflowplatform.contracts.problem.FieldValidationError;
import com.workflowplatform.contracts.problem.ProblemCode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemDetailsResponseFactoryTests {

    private final ProblemDetailsResponseFactory factory = new ProblemDetailsResponseFactory();

    @Test
    void createsAPathOnlyNoStoreProblemResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workflows/one");
        request.setQueryString(String.join("", "token=", "must-", "not-", "appear"));
        request.addHeader("X-Correlation-ID", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        ResponseEntity<ProblemDetail> response =
                factory.create(ProblemCode.COMMON_NOT_FOUND_RESOURCE, request, List.of(), null);

        assertEquals(404, response.getStatusCode().value(), "Status must match the descriptor");
        assertEquals(
                MediaType.APPLICATION_PROBLEM_JSON,
                response.getHeaders().getContentType(),
                "Problem response must use the RFC media type");
        assertEquals(
                "no-store",
                response.getHeaders().getCacheControl(),
                "Problem responses must not be cached");
        assertEquals(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                response.getHeaders().getFirst(ProblemDetailsResponseFactory.TRACE_ID_HEADER),
                "Trace header must use the resolved correlation ID");
        assertEquals(
                "/workflows/one",
                response.getBody().getInstance().toString(),
                "Problem instance must omit query data");
    }

    @Test
    void rejectsAnOversizedFieldErrorCollection() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/workflows");
        List<FieldValidationError> errors =
                Collections.nCopies(
                        51, new FieldValidationError("name", "invalid", "value is invalid"));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        factory.create(
                                ProblemCode.COMMON_VALIDATION_INVALID_FIELD, request, errors, null),
                "Oversized field error collections must be rejected");
    }
}
