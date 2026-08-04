package com.workflowplatform.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Transactional Worker persistence port for claims, heartbeats and fenced results. */
public interface WorkerTaskStore {

    ClaimResult claim(TaskReadyEvent event, String consumerName, String workerInstanceId);

    void heartbeat(ClaimedTask task, String workerInstanceId, Duration extension);

    void complete(ClaimedTask task, String workerInstanceId, NodeExecutionResult result);

    record TaskReadyEvent(
            UUID eventId,
            UUID tenantId,
            UUID nodeInstanceId,
            String eventType,
            int schemaVersion,
            String traceId) {}

    record ClaimedTask(
            TaskReadyEvent event,
            String consumerName,
            UUID workflowInstanceId,
            UUID executionId,
            String nodeId,
            String nodeType,
            int attemptNo,
            String inputPayload,
            String inputReference,
            String inputDigest,
            String configSnapshot,
            String dispatchToken,
            long leaseGeneration,
            Instant leaseExpiresAt) {

        public ClaimedTask {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(consumerName, "consumerName");
            Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(nodeType, "nodeType");
            Objects.requireNonNull(inputDigest, "inputDigest");
            Objects.requireNonNull(configSnapshot, "configSnapshot");
            Objects.requireNonNull(dispatchToken, "dispatchToken");
            Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        }
    }

    record ClaimResult(ClaimState state, ClaimedTask task) {
        public static ClaimResult claimed(ClaimedTask task) {
            return new ClaimResult(ClaimState.CLAIMED, Objects.requireNonNull(task, "task"));
        }

        public static ClaimResult duplicate() {
            return new ClaimResult(ClaimState.DUPLICATE, null);
        }

        public static ClaimResult stale() {
            return new ClaimResult(ClaimState.STALE, null);
        }
    }

    enum ClaimState {
        CLAIMED,
        DUPLICATE,
        STALE
    }

    record NodeExecutionResult(
            NodeOutcome outcome,
            String outputReference,
            String outputDigest,
            String errorCode,
            String errorSummaryRedacted,
            boolean retryable,
            Instant nextScheduleAt,
            Duration retryAfter) {

        private static final int MAXIMUM_REFERENCE_LENGTH = 8192;
        private static final int MAXIMUM_ERROR_SUMMARY_LENGTH = 1000;
        private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
        private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,79}");

        public NodeExecutionResult {
            Objects.requireNonNull(outcome, "outcome");
            if ((outputReference == null) != (outputDigest == null)) {
                throw new IllegalArgumentException("output reference and digest must be paired");
            }
            if ((errorCode == null) != (errorSummaryRedacted == null)) {
                throw new IllegalArgumentException("error code and summary must be paired");
            }
            if (outputReference != null
                    && (outputReference.length() > MAXIMUM_REFERENCE_LENGTH
                            || !SHA_256.matcher(outputDigest).matches())) {
                throw new IllegalArgumentException("node output reference or digest is invalid");
            }
            if (errorCode != null
                    && (!ERROR_CODE.matcher(errorCode).matches()
                            || errorSummaryRedacted.isBlank()
                            || errorSummaryRedacted.length() > MAXIMUM_ERROR_SUMMARY_LENGTH
                            || errorSummaryRedacted.indexOf('\n') >= 0
                            || errorSummaryRedacted.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("node error classification is invalid");
            }
            boolean scheduled =
                    outcome == NodeOutcome.RETRY_WAIT || outcome == NodeOutcome.WAITING_TIMER;
            if (scheduled != (nextScheduleAt != null)) {
                throw new IllegalArgumentException("scheduled outcomes require nextScheduleAt");
            }
            if (retryAfter != null
                    && (!retryable
                            || retryAfter.isNegative()
                            || retryAfter.isZero()
                            || retryAfter.compareTo(Duration.ofDays(1)) > 0)) {
                throw new IllegalArgumentException("Retry-After must be positive and bounded");
            }
            if (outcome == NodeOutcome.SUCCEEDED && outputReference == null) {
                throw new IllegalArgumentException("successful node outcome requires output");
            }
            if (outcome == NodeOutcome.FAILED && errorCode == null) {
                throw new IllegalArgumentException("failed node outcome requires an error");
            }
            if (outcome == NodeOutcome.RETRY_WAIT && errorCode == null) {
                throw new IllegalArgumentException("retry wait requires an error classification");
            }
            if (retryable && errorCode == null) {
                throw new IllegalArgumentException("retryable classification requires an error");
            }
        }

        public static NodeExecutionResult success(String reference, String digest) {
            return new NodeExecutionResult(
                    NodeOutcome.SUCCEEDED, reference, digest, null, null, false, null, null);
        }

        public static NodeExecutionResult waitingTimer(Instant resumeAt) {
            return new NodeExecutionResult(
                    NodeOutcome.WAITING_TIMER, null, null, null, null, false, resumeAt, null);
        }

        public static NodeExecutionResult failure(String code, String summary) {
            return failure(code, summary, false);
        }

        public static NodeExecutionResult failure(String code, String summary, boolean retryable) {
            return failure(code, summary, retryable, null);
        }

        public static NodeExecutionResult failure(
                String code, String summary, boolean retryable, Duration retryAfter) {
            return new NodeExecutionResult(
                    NodeOutcome.FAILED, null, null, code, summary, retryable, null, retryAfter);
        }

        public static NodeExecutionResult retryWait(
                String code, String summary, Instant nextScheduleAt) {
            return new NodeExecutionResult(
                    NodeOutcome.RETRY_WAIT, null, null, code, summary, true, nextScheduleAt, null);
        }
    }

    enum NodeOutcome {
        SUCCEEDED,
        FAILED,
        RETRY_WAIT,
        WAITING_TIMER
    }
}
