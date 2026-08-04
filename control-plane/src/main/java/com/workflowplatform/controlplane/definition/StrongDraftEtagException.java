package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import java.io.Serial;
import java.util.Objects;

/** Safe failure raised when an If-Match header is absent or not one exact strong draft ETag. */
public final class StrongDraftEtagException extends ApiProblemException {

    @Serial private static final long serialVersionUID = 1L;

    private final Failure failureValue;

    StrongDraftEtagException(Failure failure) {
        this(failure, null);
    }

    StrongDraftEtagException(Failure failure, Throwable cause) {
        super(problemCode(failure), null, cause);
        failureValue = Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() {
        return failureValue;
    }

    public enum Failure {
        REQUIRED,
        INVALID
    }

    private static ProblemCode problemCode(Failure failure) {
        return failure == Failure.REQUIRED
                ? ProblemCode.DEFINITION_PRECONDITION_REQUIRED_IF_MATCH
                : ProblemCode.DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH;
    }
}
