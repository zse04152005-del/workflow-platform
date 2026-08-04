package com.workflowplatform.controlplane.api.error;

import com.workflowplatform.contracts.problem.ProblemCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serial;
import java.util.Objects;

/** Explicit application failure that is safe to expose through the frozen error catalog. */
@SuppressFBWarnings(
        value = "CT_CONSTRUCTOR_THROW",
        justification =
                "Constructors validate frozen catalog inputs and may fail before the exception object escapes")
public class ApiProblemException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final ProblemCode codeValue;
    private final Integer retryDelaySeconds;

    protected ApiProblemException(ProblemCode problemCode, Integer retryAfterSeconds) {
        this(problemCode, retryAfterSeconds, null);
    }

    protected ApiProblemException(
            ProblemCode problemCode, Integer retryAfterSeconds, Throwable cause) {
        super(problemCode.descriptor().defaultDetail(), cause);
        this.codeValue = Objects.requireNonNull(problemCode, "problemCode");
        this.retryDelaySeconds = retryAfterSeconds;
    }

    public static ApiProblemException of(ProblemCode problemCode) {
        return new ApiProblemException(problemCode, null);
    }

    public static ApiProblemException causedBy(ProblemCode problemCode, Throwable cause) {
        return new ApiProblemException(problemCode, null, Objects.requireNonNull(cause, "cause"));
    }

    public static ApiProblemException retryAfter(ProblemCode problemCode, int seconds) {
        Objects.requireNonNull(problemCode, "problemCode");
        if (!problemCode.descriptor().retryable()) {
            throw new IllegalArgumentException("Retry-After is only valid for retryable errors");
        }
        if (seconds < 1 || seconds > 86_400) {
            throw new IllegalArgumentException("Retry-After must be between 1 and 86400 seconds");
        }
        return new ApiProblemException(problemCode, seconds);
    }

    public ProblemCode problemCode() {
        return codeValue;
    }

    public Integer retryAfterSeconds() {
        return retryDelaySeconds;
    }
}
