package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import java.io.Serial;
import java.util.Objects;

/** Payload-safe draft persistence failure mapped by the HTTP boundary. */
public final class WorkflowDraftOperationException extends ApiProblemException {

    @Serial private static final long serialVersionUID = 1L;

    private final Failure failureValue;

    public WorkflowDraftOperationException(Failure failure) {
        this(failure, null);
    }

    public WorkflowDraftOperationException(Failure failure, Throwable cause) {
        super(problemCode(failure), null, cause);
        failureValue = Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() {
        return failureValue;
    }

    public enum Failure {
        NOT_FOUND,
        CONFLICT,
        PRECONDITION_FAILED,
        DEFINITION_NAME_MISMATCH
    }

    private static ProblemCode problemCode(Failure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case NOT_FOUND -> ProblemCode.COMMON_NOT_FOUND_RESOURCE;
            case CONFLICT -> ProblemCode.COMMON_CONFLICT_STATE;
            case PRECONDITION_FAILED -> ProblemCode.DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH;
            case DEFINITION_NAME_MISMATCH -> ProblemCode.COMMON_VALIDATION_INVALID_FIELD;
        };
    }
}
