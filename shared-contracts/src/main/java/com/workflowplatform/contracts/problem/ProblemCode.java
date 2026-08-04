package com.workflowplatform.contracts.problem;

import java.net.URI;

/** Stable public error-code catalog shared by HTTP producers and consumers. */
public enum ProblemCode {
    COMMON_VALIDATION_INVALID_FIELD(
            "invalid-field",
            "Request validation failed",
            400,
            "One or more request fields are invalid.",
            false),
    COMMON_VALIDATION_MALFORMED_REQUEST(
            "malformed-request",
            "Malformed request",
            400,
            "The request body or syntax is malformed.",
            false),
    COMMON_VALIDATION_MISSING_PARAMETER(
            "missing-parameter",
            "Required parameter is missing",
            400,
            "A required request parameter is missing.",
            false),
    COMMON_VALIDATION_TYPE_MISMATCH(
            "type-mismatch",
            "Request value has the wrong type",
            400,
            "A request value could not be converted to the required type.",
            false),
    AUTH_AUTHENTICATION_REQUIRED(
            "authentication-required",
            "Authentication required",
            401,
            "Valid authentication credentials are required.",
            false),
    AUTH_AUTHORIZATION_DENIED(
            "authorization-denied",
            "Access denied",
            403,
            "The authenticated principal is not allowed to perform this operation.",
            false),
    COMMON_NOT_FOUND_RESOURCE(
            "resource-not-found",
            "Resource not found",
            404,
            "The requested resource was not found.",
            false),
    COMMON_CONFLICT_STATE(
            "state-conflict",
            "Resource state conflict",
            409,
            "The operation conflicts with the current resource state.",
            false),
    DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH(
            "etag-mismatch",
            "Draft precondition failed",
            412,
            "The supplied draft ETag does not match the current revision.",
            false),
    DEFINITION_PRECONDITION_REQUIRED_IF_MATCH(
            "if-match-required",
            "Draft precondition required",
            428,
            "A strong If-Match header is required for this operation.",
            false),
    COMMON_RATE_LIMIT_EXCEEDED(
            "rate-limit-exceeded",
            "Rate limit exceeded",
            429,
            "The request rate limit has been exceeded.",
            true),
    CONNECTOR_DEPENDENCY_UNAVAILABLE(
            "dependency-unavailable",
            "Dependency unavailable",
            503,
            "A required dependency is temporarily unavailable.",
            true),
    COMMON_INTERNAL_UNEXPECTED(
            "unexpected-error",
            "Unexpected server error",
            500,
            "An unexpected error occurred.",
            false);

    public static final String TYPE_BASE_URI = "https://api.workflow-platform.example/problems/";

    private final ProblemDescriptor metadata;

    ProblemCode(
            String typeSlug, String title, int status, String defaultDetail, boolean retryable) {
        metadata =
                new ProblemDescriptor(
                        name(),
                        URI.create(TYPE_BASE_URI + typeSlug),
                        title,
                        status,
                        defaultDetail,
                        retryable);
    }

    public ProblemDescriptor descriptor() {
        return metadata;
    }
}
