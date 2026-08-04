package com.workflowplatform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class WorkerExecutionServiceTests {

    private static final String CONSUMER_NAME = "workflow-worker";
    private static final String WORKER_ID = "worker-a";

    @Test
    void returnsDuplicateAndStaleWithoutExecuting() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        WorkerExecutionService service = service(store, List.of(executor));
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.duplicate())
                .thenReturn(WorkerTaskStore.ClaimResult.stale());

        assertEquals(WorkerExecutionService.ProcessResult.DUPLICATE, service.process(payload()));
        assertEquals(WorkerExecutionService.ProcessResult.STALE, service.process(payload()));
        verifyNoInteractions(executor);
    }

    @Test
    void executesTheMatchingPluginAndPersistsItsResult() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        WorkerTaskStore.ClaimedTask task = task("custom");
        WorkerTaskStore.NodeExecutionResult success =
                WorkerTaskStore.NodeExecutionResult.success("output-ref", "a".repeat(64));
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        when(executor.supports("custom")).thenReturn(true);
        when(executor.execute(task)).thenReturn(success);

        WorkerExecutionService.ProcessResult result =
                service(store, List.of(executor)).process(payload());

        assertEquals(WorkerExecutionService.ProcessResult.PROCESSED, result);
        verify(store).complete(task, WORKER_ID, success);
    }

    @Test
    void persistsAControlledFailureWhenNoPluginMatches() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerTaskStore.ClaimedTask task = task("missing");
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        ArgumentCaptor<WorkerTaskStore.NodeExecutionResult> result =
                ArgumentCaptor.forClass(WorkerTaskStore.NodeExecutionResult.class);

        assertEquals(
                WorkerExecutionService.ProcessResult.PROCESSED,
                service(store, List.of()).process(payload()));
        verify(store).complete(eq(task), eq(WORKER_ID), result.capture());
        assertEquals("NODE_TYPE_UNSUPPORTED", result.getValue().errorCode());
    }

    @Test
    void redactsUnexpectedPluginFailures() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        WorkerTaskStore.ClaimedTask task = task("custom");
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        when(executor.supports("custom")).thenReturn(true);
        when(executor.execute(task)).thenThrow(new IllegalStateException("sensitive failure"));
        ArgumentCaptor<WorkerTaskStore.NodeExecutionResult> result =
                ArgumentCaptor.forClass(WorkerTaskStore.NodeExecutionResult.class);

        service(store, List.of(executor)).process(payload());

        verify(store).complete(eq(task), eq(WORKER_ID), result.capture());
        assertEquals("NODE_EXECUTION_UNEXPECTED", result.getValue().errorCode());
        assertEquals(
                "node execution failed unexpectedly", result.getValue().errorSummaryRedacted());
    }

    @Test
    void enforcesThePersistedNodeTimeoutAndClassifiesItAsRetryable() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        WorkerTaskStore.ClaimedTask task = task("custom", "{\"_timeout\":\"PT0.01S\"}");
        CountDownLatch started = new CountDownLatch(1);
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        when(executor.supports("custom")).thenReturn(true);
        when(executor.execute(task))
                .thenAnswer(
                        invocation -> {
                            started.countDown();
                            Thread.sleep(500);
                            return WorkerTaskStore.NodeExecutionResult.success(
                                    "never", "b".repeat(64));
                        });
        ArgumentCaptor<WorkerTaskStore.NodeExecutionResult> result =
                ArgumentCaptor.forClass(WorkerTaskStore.NodeExecutionResult.class);

        service(store, List.of(executor)).process(payload());

        assertEquals(0L, started.getCount());
        verify(store).complete(eq(task), eq(WORKER_ID), result.capture());
        assertEquals("NODE_TIMEOUT", result.getValue().errorCode());
        assertTrue(result.getValue().retryable());
    }

    @Test
    void externalResultQueryPreventsRetryWhenTheRemoteOperationAlreadySucceeded() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        ExternalResultQueryHook queryHook = mock(ExternalResultQueryHook.class);
        WorkerTaskStore.ClaimedTask task = task("custom");
        WorkerTaskStore.NodeExecutionResult ambiguous =
                WorkerTaskStore.NodeExecutionResult.failure(
                        "HTTP_TRANSPORT_ERROR", "managed HTTP transport failed", true);
        WorkerTaskStore.NodeExecutionResult recovered =
                WorkerTaskStore.NodeExecutionResult.success("external-result", "d".repeat(64));
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        when(executor.supports("custom")).thenReturn(true);
        when(executor.execute(task)).thenReturn(ambiguous);
        when(queryHook.supports("custom")).thenReturn(true);
        when(queryHook.query(task, ambiguous)).thenReturn(Optional.of(recovered));

        service(store, List.of(executor), List.of(queryHook)).process(payload());

        verify(store).complete(task, WORKER_ID, recovered);
    }

    @Test
    void failedOrNonTerminalExternalQueriesFallBackToTheOriginalRetryableFailure() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        ExternalResultQueryHook failingHook = mock(ExternalResultQueryHook.class);
        ExternalResultQueryHook nonTerminalHook = mock(ExternalResultQueryHook.class);
        WorkerTaskStore.ClaimedTask task = task("custom");
        WorkerTaskStore.NodeExecutionResult ambiguous =
                WorkerTaskStore.NodeExecutionResult.failure(
                        "NODE_TIMEOUT", "node execution timed out", true);
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        when(executor.supports("custom")).thenReturn(true);
        when(executor.execute(task)).thenReturn(ambiguous);
        when(failingHook.supports("custom")).thenThrow(new IllegalStateException("secret"));
        when(nonTerminalHook.supports("custom")).thenReturn(true);
        when(nonTerminalHook.query(task, ambiguous)).thenReturn(Optional.of(ambiguous));

        service(store, List.of(executor), List.of(failingHook, nonTerminalHook)).process(payload());

        verify(store).complete(task, WORKER_ID, ambiguous);
    }

    @Test
    void rejectsInvalidPersistedTimeoutBeforeCallingThePlugin() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerNodeExecutor executor = mock(WorkerNodeExecutor.class);
        WorkerTaskStore.ClaimedTask task = task("custom", "{\"_timeout\":\"PT31M\"}");
        when(store.claim(any(), eq(CONSUMER_NAME), eq(WORKER_ID)))
                .thenReturn(WorkerTaskStore.ClaimResult.claimed(task));
        when(executor.supports("custom")).thenReturn(true);

        service(store, List.of(executor)).process(payload());

        verify(executor).supports("custom");
        verify(executor, never()).execute(any());
    }

    @Test
    void rejectsMalformedOrUnsupportedEventEnvelopes() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        WorkerExecutionService service = service(store, List.of());

        assertThrows(IllegalArgumentException.class, () -> service.process("{"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.process(
                                payload().replace("workflow.task.ready", "workflow.task.bad")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.process(
                                payload()
                                        .replace(
                                                "1234567890abcdef1234567890abcdef",
                                                "00000000000000000000000000000000")));
        verifyNoInteractions(store);
    }

    @Test
    void requiresStableConsumerAndWorkerIdentities() {
        WorkerTaskStore store = mock(WorkerTaskStore.class);
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerExecutionService(store, mapper, List.of(), null, WORKER_ID));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkerExecutionService(
                                store, mapper, List.of(), CONSUMER_NAME, "bad id"));
    }

    private static WorkerExecutionService service(
            WorkerTaskStore store, List<WorkerNodeExecutor> executors) {
        return new WorkerExecutionService(
                store, new ObjectMapper(), executors, CONSUMER_NAME, WORKER_ID);
    }

    private static WorkerExecutionService service(
            WorkerTaskStore store,
            List<WorkerNodeExecutor> executors,
            List<ExternalResultQueryHook> queryHooks) {
        return new WorkerExecutionService(
                store, new ObjectMapper(), executors, queryHooks, CONSUMER_NAME, WORKER_ID);
    }

    private static WorkerTaskStore.ClaimedTask task(String nodeType) {
        return task(nodeType, "{}");
    }

    private static WorkerTaskStore.ClaimedTask task(String nodeType, String configSnapshot) {
        WorkerTaskStore.TaskReadyEvent event =
                new WorkerTaskStore.TaskReadyEvent(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c611"),
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c612"),
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c613"),
                        "workflow.task.ready",
                        1,
                        "1234567890abcdef1234567890abcdef");
        return new WorkerTaskStore.ClaimedTask(
                event,
                CONSUMER_NAME,
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c614"),
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c615"),
                "node-a",
                nodeType,
                1,
                "{}",
                "input-ref",
                "c".repeat(64),
                configSnapshot,
                "dispatch-token",
                1L,
                Instant.now().plusSeconds(60));
    }

    private static String payload() {
        return """
                {
                  "eventId": "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c611",
                  "tenantId": "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c612",
                  "eventType": "workflow.task.ready",
                  "schemaVersion": 1,
                  "traceId": "1234567890abcdef1234567890abcdef",
                  "payload": {"entityId": "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c613"}
                }
                """;
    }
}
