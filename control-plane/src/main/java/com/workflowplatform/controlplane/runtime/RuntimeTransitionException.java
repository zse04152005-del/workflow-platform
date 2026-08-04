package com.workflowplatform.controlplane.runtime;

import java.io.Serial;
import java.util.Objects;

/** Stable state-transition failure independent from database exception text. */
public final class RuntimeTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public enum Failure {
        NOT_FOUND,
        VERSION_CONFLICT,
        INVALID_STATE_TRANSITION,
        ACTOR_NOT_ALLOWED,
        INVALID_EFFECTS,
        FENCING_TOKEN_CONFLICT
    }

    private final Failure failureValue;

    public RuntimeTransitionException(Failure failure) {
        super(Objects.requireNonNull(failure, "failure").name());
        this.failureValue = failure;
    }

    public Failure failure() {
        return failureValue;
    }
}
