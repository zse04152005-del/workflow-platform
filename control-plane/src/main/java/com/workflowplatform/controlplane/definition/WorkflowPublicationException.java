package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import java.io.Serial;
import java.util.Objects;

/** Payload-safe publication failure mapped through the frozen RFC 9457 catalog. */
public final class WorkflowPublicationException extends ApiProblemException {

    @Serial private static final long serialVersionUID = 1L;

    private final Failure failureValue;

    public WorkflowPublicationException(Failure failure) {
        this(failure, null);
    }

    public WorkflowPublicationException(Failure failure, Throwable cause) {
        super(problemCode(failure), null, cause);
        failureValue = Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() {
        return failureValue;
    }

    public enum Failure {
        NOT_FOUND,
        CONFLICT,
        REVISION_MISMATCH,
        VALIDATION_FAILED
    }

    private static ProblemCode problemCode(Failure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case NOT_FOUND -> ProblemCode.COMMON_NOT_FOUND_RESOURCE;
            case CONFLICT, REVISION_MISMATCH, VALIDATION_FAILED ->
                    ProblemCode.COMMON_CONFLICT_STATE;
        };
    }
}
