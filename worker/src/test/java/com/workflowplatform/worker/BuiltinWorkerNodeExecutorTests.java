package com.workflowplatform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class BuiltinWorkerNodeExecutorTests {

    private final BuiltinWorkerNodeExecutor executor =
            new BuiltinWorkerNodeExecutor(new ObjectMapper());

    @Test
    void reportsOnlyTheBuiltInNodeTypesAsSupported() {
        assertTrue(executor.supports("start"));
        assertTrue(executor.supports("end"));
        assertTrue(executor.supports("condition"));
        assertTrue(executor.supports("delay"));
        assertFalse(executor.supports("http"));
    }

    @Test
    void executesTerminalNodesAndRejectsUnknownNodes() {
        WorkerTaskStore.NodeExecutionResult start = executor.execute(task("start", 1, "{}"));
        WorkerTaskStore.NodeExecutionResult unsupported = executor.execute(task("http", 1, "{}"));

        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, start.outcome());
        assertEquals("d".repeat(64), start.outputDigest());
        assertEquals(WorkerTaskStore.NodeOutcome.FAILED, unsupported.outcome());
        assertEquals("NODE_TYPE_UNSUPPORTED", unsupported.errorCode());
    }

    @Test
    void evaluatesTheDeterministicConditionSubset() {
        WorkerTaskStore.NodeExecutionResult conditionTrue =
                executor.execute(task("condition", 1, "{\"expression\":\"true\"}"));
        WorkerTaskStore.NodeExecutionResult conditionFalse =
                executor.execute(task("condition", 1, "{\"expression\":\"false\"}"));
        WorkerTaskStore.NodeExecutionResult inputExpression =
                executor.execute(
                        task(
                                "condition",
                                1,
                                "{\"expression\":\"input.total > 1 && input.enabled\"}",
                                "{\"total\":2,\"enabled\":true}"));
        WorkerTaskStore.NodeExecutionResult unsupported =
                executor.execute(task("condition", 1, "{\"expression\":\"size(input) > 1\"}"));
        WorkerTaskStore.NodeExecutionResult malformed = executor.execute(task("condition", 1, "{"));

        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, conditionTrue.outcome());
        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, conditionFalse.outcome());
        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, inputExpression.outcome());
        assertTrue(conditionTrue.outputReference().startsWith("inline:"));
        assertNotNull(conditionFalse.outputDigest());
        assertEquals("CONDITION_EVALUATION_FAILED", unsupported.errorCode());
        assertEquals("NODE_CONFIG_INVALID", malformed.errorCode());
    }

    @Test
    void schedulesValidDelaysAndCompletesAResumedDelay() {
        Instant before = Instant.now();
        WorkerTaskStore.NodeExecutionResult scheduled =
                executor.execute(task("delay", 1, "{\"duration\":\"PT2S\"}"));
        WorkerTaskStore.NodeExecutionResult resumed =
                executor.execute(task("delay", 2, "{\"duration\":\"PT2S\"}"));

        assertEquals(WorkerTaskStore.NodeOutcome.WAITING_TIMER, scheduled.outcome());
        assertTrue(scheduled.nextScheduleAt().isAfter(before));
        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, resumed.outcome());
    }

    @Test
    void rejectsOutOfRangeAndMalformedDelays() {
        WorkerTaskStore.NodeExecutionResult tooShort =
                executor.execute(task("delay", 1, "{\"duration\":\"PT0S\"}"));
        WorkerTaskStore.NodeExecutionResult tooLong =
                executor.execute(task("delay", 1, "{\"duration\":\"PT24H0.001S\"}"));
        WorkerTaskStore.NodeExecutionResult malformed = executor.execute(task("delay", 1, "{"));

        assertEquals("DELAY_DURATION_INVALID", tooShort.errorCode());
        assertEquals("DELAY_DURATION_INVALID", tooLong.errorCode());
        assertEquals("NODE_CONFIG_INVALID", malformed.errorCode());
    }

    private static WorkerTaskStore.ClaimedTask task(
            String nodeType, int attemptNo, String configSnapshot) {
        return task(nodeType, attemptNo, configSnapshot, "{}");
    }

    private static WorkerTaskStore.ClaimedTask task(
            String nodeType, int attemptNo, String configSnapshot, String inputPayload) {
        UUID tenantId = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c601");
        UUID nodeId = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c602");
        WorkerTaskStore.TaskReadyEvent event =
                new WorkerTaskStore.TaskReadyEvent(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c603"),
                        tenantId,
                        nodeId,
                        "workflow.task.ready",
                        1,
                        "1234567890abcdef1234567890abcdef");
        return new WorkerTaskStore.ClaimedTask(
                event,
                "workflow-worker",
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c604"),
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c605"),
                "node-a",
                nodeType,
                attemptNo,
                inputPayload,
                "input-ref",
                "d".repeat(64),
                configSnapshot,
                "dispatch-token",
                1L,
                Instant.now().plusSeconds(60));
    }
}
