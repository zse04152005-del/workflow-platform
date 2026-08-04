package com.workflowplatform.payload;

/** Stable storage failure classification that never exposes provider credentials or bodies. */
public final class PayloadStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final Reason failureReason;

    public PayloadStorageException(Reason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public PayloadStorageException(Reason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public Reason reason() {
        return failureReason;
    }

    public enum Reason {
        INVALID_REFERENCE,
        ACCESS_DENIED,
        NOT_FOUND,
        INTEGRITY,
        RETENTION,
        BACKEND
    }
}
