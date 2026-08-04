package com.workflowplatform.controlplane.definition;

import java.util.Objects;

/** Stable runtime failure raised when an evaluator crosses its published resource boundary. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class ExpressionSandboxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    ExpressionSandboxException(Reason reason) {
        super(
                "Expression sandbox limit exceeded: "
                        + Objects.requireNonNull(reason, "reason").wireName());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        STEP_LIMIT("step_limit"),
        TIME_LIMIT("time_limit"),
        RESULT_BYTES("result_bytes"),
        RESULT_DEPTH("result_depth"),
        RESULT_ENTRIES("result_entries"),
        RESULT_TYPE("result_type");

        private final String wireName;

        Reason(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
