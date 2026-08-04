package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Typed, secret-free state-dependent values applied atomically with one transition. */
public record RuntimeTransitionEffects(
        Instant nextScheduleAt,
        String workerId,
        Instant leaseExpiresAt,
        Long expectedLeaseGeneration,
        UUID workflowInstanceId,
        String outputReference,
        String outputDigest,
        String errorCode,
        String errorSummaryRedacted) {

    private static final int MAX_REFERENCE_LENGTH = 1024;
    private static final int MAX_WORKER_ID_LENGTH = 160;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 2000;
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,119}");

    public RuntimeTransitionEffects {
        if (workerId != null && (workerId.isBlank() || workerId.length() > MAX_WORKER_ID_LENGTH)) {
            throw new IllegalArgumentException("workerId must contain 1 to 160 characters");
        }
        if (expectedLeaseGeneration != null && expectedLeaseGeneration <= 0) {
            throw new IllegalArgumentException("expectedLeaseGeneration must be positive");
        }
        if ((outputReference == null) != (outputDigest == null)) {
            throw new IllegalArgumentException(
                    "output reference and digest must be supplied together");
        }
        if (outputReference != null
                && (outputReference.isBlank() || outputReference.length() > MAX_REFERENCE_LENGTH)) {
            throw new IllegalArgumentException("outputReference must contain 1 to 1024 characters");
        }
        if (outputDigest != null && !DIGEST.matcher(outputDigest).matches()) {
            throw new IllegalArgumentException("outputDigest must be a lower-case SHA-256 digest");
        }
        if (errorCode != null && !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable upper-case code");
        }
        if (errorSummaryRedacted != null
                && (errorSummaryRedacted.isBlank()
                        || errorSummaryRedacted.length() > MAX_ERROR_SUMMARY_LENGTH)) {
            throw new IllegalArgumentException(
                    "errorSummaryRedacted must contain 1 to 2000 characters");
        }
        if (errorSummaryRedacted != null && errorCode == null) {
            throw new IllegalArgumentException("an error summary requires an error code");
        }
    }

    public static RuntimeTransitionEffects none() {
        return new RuntimeTransitionEffects(null, null, null, null, null, null, null, null, null);
    }

    public static RuntimeTransitionEffects noneWithError(String errorCode, String redactedSummary) {
        return new RuntimeTransitionEffects(
                null, null, null, null, null, null, null, errorCode, redactedSummary);
    }

    public boolean hasOutput() {
        return outputReference != null;
    }

    public boolean hasError() {
        return errorCode != null;
    }

    public boolean hasWorkerGuard() {
        return workerId != null && expectedLeaseGeneration != null;
    }

    public String requireWorkerId() {
        return Objects.requireNonNull(workerId, "workerId");
    }

    public long requireExpectedLeaseGeneration() {
        return Objects.requireNonNull(expectedLeaseGeneration, "expectedLeaseGeneration");
    }
}
