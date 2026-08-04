package com.workflowplatform.controlplane.runtime.internal;

/** Shared status rules for persistent pause and cooperative-cancel convergence. */
final class RuntimeControlStateSupport {

    private RuntimeControlStateSupport() {
        throw new AssertionError("no instances");
    }

    static boolean isActiveExecution(String status) {
        return "DISPATCHED".equals(status) || "RUNNING".equals(status);
    }

    static String cancellationCommand(String status) {
        return switch (status) {
            case "PENDING" -> "cancel_pending";
            case "READY" -> "cancel_ready";
            case "DISPATCHED" -> "cancel_dispatched";
            case "RETRY_WAIT" -> "cancel_retry";
            case "WAITING_TIMER" -> "cancel_timer";
            case "WAITING_APPROVAL" -> "cancel_approval";
            default -> null;
        };
    }
}
