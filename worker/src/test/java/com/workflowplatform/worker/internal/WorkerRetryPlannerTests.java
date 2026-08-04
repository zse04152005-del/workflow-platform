package com.workflowplatform.worker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.worker.WorkerTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class WorkerRetryPlannerTests {

    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");

    @Test
    void plansFixedLinearAndExponentialDelays() {
        assertDelay(
                "{\"maxAttempts\":5,\"strategy\":\"fixed\",\"initialDelay\":\"PT2S\"}",
                2,
                Duration.ofSeconds(2));
        assertDelay(
                "{\"maxAttempts\":5,\"strategy\":\"linear\",\"initialDelay\":\"PT2S\","
                        + "\"increment\":\"PT3S\"}",
                3,
                Duration.ofSeconds(8));
        assertDelay(
                "{\"maxAttempts\":5,\"strategy\":\"exponential\","
                        + "\"initialDelay\":\"PT2S\",\"multiplier\":2,\"maxDelay\":\"PT7S\"}",
                4,
                Duration.ofSeconds(7));
    }

    @Test
    void appliesRetryAfterAndStopsAtAttemptOrElapsedWindow() {
        String policy =
                "{\"maxAttempts\":3,\"strategy\":\"fixed\",\"initialDelay\":\"PT2S\","
                        + "\"maxElapsedTime\":\"PT10S\",\"jitterRatio\":0}";
        WorkerTaskStore.NodeExecutionResult delayed =
                planner()
                        .plan(
                                task(policy, 1),
                                WorkerTaskStore.NodeExecutionResult.failure(
                                        "HTTP_DOWNSTREAM_ERROR",
                                        "downstream unavailable",
                                        true,
                                        Duration.ofSeconds(6)),
                                NOW.minusSeconds(1),
                                NOW);
        WorkerTaskStore.NodeExecutionResult attemptExhausted =
                planner().plan(task(policy, 3), retryableFailure(), NOW.minusSeconds(1), NOW);
        WorkerTaskStore.NodeExecutionResult windowExhausted =
                planner().plan(task(policy, 1), retryableFailure(), NOW.minusSeconds(9), NOW);

        assertEquals(NOW.plusSeconds(6), delayed.nextScheduleAt());
        assertEquals(WorkerTaskStore.NodeOutcome.FAILED, attemptExhausted.outcome());
        assertEquals(WorkerTaskStore.NodeOutcome.FAILED, windowExhausted.outcome());
        assertFalse(attemptExhausted.retryable());
    }

    @Test
    void terminalizesMissingPolicyAndRejectsMalformedPolicy() {
        WorkerTaskStore.NodeExecutionResult withoutPolicy =
                planner().plan(task(null, 1), retryableFailure(), NOW.minusSeconds(1), NOW);

        assertEquals(WorkerTaskStore.NodeOutcome.FAILED, withoutPolicy.outcome());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        planner()
                                .plan(
                                        task(
                                                "{\"maxAttempts\":3,\"strategy\":\"unknown\","
                                                        + "\"initialDelay\":\"PT1S\"}",
                                                1),
                                        retryableFailure(),
                                        NOW.minusSeconds(1),
                                        NOW));
    }

    private static void assertDelay(String policy, int attemptNo, Duration expected) {
        WorkerTaskStore.NodeExecutionResult result =
                planner()
                        .plan(
                                task(policy, attemptNo),
                                retryableFailure(),
                                NOW.minusSeconds(1),
                                NOW);
        assertEquals(WorkerTaskStore.NodeOutcome.RETRY_WAIT, result.outcome());
        assertEquals(NOW.plus(expected), result.nextScheduleAt());
    }

    private static WorkerRetryPlanner planner() {
        return new WorkerRetryPlanner(new ObjectMapper());
    }

    private static WorkerTaskStore.NodeExecutionResult retryableFailure() {
        return WorkerTaskStore.NodeExecutionResult.failure("NODE_TIMEOUT", "node timed out", true);
    }

    private static WorkerTaskStore.ClaimedTask task(String retryPolicy, int attemptNo) {
        UUID nodeId = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c703");
        WorkerTaskStore.TaskReadyEvent event =
                new WorkerTaskStore.TaskReadyEvent(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c701"),
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c702"),
                        nodeId,
                        "workflow.task.ready",
                        1,
                        "1234567890abcdef1234567890abcdef");
        String snapshot = retryPolicy == null ? "{}" : "{\"_retry\":" + retryPolicy + "}";
        return new WorkerTaskStore.ClaimedTask(
                event,
                "workflow-worker",
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c704"),
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c705"),
                "http-call",
                "http",
                attemptNo,
                "{}",
                null,
                "d".repeat(64),
                snapshot,
                "dispatch-token",
                1,
                NOW.plusSeconds(60));
    }
}
