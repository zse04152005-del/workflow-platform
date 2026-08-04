package com.workflowplatform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class ManagedHttpWorkerNodeExecutorTests {

    private static final String HOST = "api.example.test";
    private static final String ENDPOINT = "https://api.example.test";
    private static final String INVALID_CONFIG = "NODE_CONFIG_INVALID";
    private static final String ENDPOINT_DENIED = "HTTP_ENDPOINT_DENIED";

    @Test
    void mapsManagedRequestAndSelectsBoundedResponse() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        ManagedHttpWorkerNodeExecutor executor =
                executor(
                        1024,
                        (request, policy, maximumBytes) -> {
                            policy.validate(request.uri());
                            called.set(true);
                            assertEquals("POST", request.method());
                            assertEquals("request-1", request.headers().get("X-Request-Id"));
                            assertTrue(
                                    new String(request.body(), StandardCharsets.UTF_8)
                                            .contains("\"amount\":10"));
                            assertEquals(1024, maximumBytes);
                            return new ManagedHttpWorkerNodeExecutor.Response(
                                    200, "{\"id\":\"accepted\"}".getBytes(StandardCharsets.UTF_8));
                        });

        WorkerTaskStore.NodeExecutionResult result =
                executor.execute(
                        task(
                                """
                                {
                                  "_managedEndpoint":"https://api.example.test/v1/",
                                  "_timeout":"PT5S",
                                  "method":"POST",
                                  "pathTemplate":"orders",
                                  "headers":{"X-Request-Id":"input.request_id"},
                                  "bodyExpression":"input",
                                  "resultExpression":"response.body.id"
                                }
                                """));

        assertTrue(called.get());
        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.outputReference().startsWith("inline:"));
        assertEquals(64, result.outputDigest().length());
    }

    @Test
    void rejectsPrivateDnsHeaderInjectionAndUnmanagedScheme() throws Exception {
        ManagedHttpWorkerNodeExecutor privateAddress =
                new ManagedHttpWorkerNodeExecutor(
                        new ObjectMapper(),
                        Set.of(HOST),
                        1024,
                        ignored -> List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})),
                        (request, policy, maximumBytes) ->
                                new ManagedHttpWorkerNodeExecutor.Response(200, new byte[0]));
        WorkerTaskStore.NodeExecutionResult dnsDenied =
                privateAddress.execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult schemeDenied =
                executor(1024, successfulExchange())
                        .execute(task(baseConfig("http://api.example.test")));
        WorkerTaskStore.NodeExecutionResult headerDenied =
                executor(1024, successfulExchange())
                        .execute(task(config("\"headers\":{\"Host\":\"input.request_id\"}")));

        assertEquals(ENDPOINT_DENIED, dnsDenied.errorCode());
        assertEquals(ENDPOINT_DENIED, schemeDenied.errorCode());
        assertEquals("NODE_CONFIG_INVALID", headerDenied.errorCode());
        assertFalse(dnsDenied.retryable());
    }

    @Test
    void classifiesServerFailuresAndRejectsOversizedResponses() throws Exception {
        WorkerTaskStore.NodeExecutionResult serverFailure =
                executor(
                                1024,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                503,
                                                "unavailable".getBytes(StandardCharsets.UTF_8)))
                        .execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult oversized =
                executor(
                                4,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                200, "12345".getBytes(StandardCharsets.UTF_8)))
                        .execute(task(baseConfig(ENDPOINT)));

        assertEquals("HTTP_DOWNSTREAM_ERROR", serverFailure.errorCode());
        assertTrue(serverFailure.retryable());
        assertEquals("HTTP_RESPONSE_TOO_LARGE", oversized.errorCode());
        assertFalse(oversized.retryable());
    }

    @Test
    void preservesBoundedRetryAfterHintsForTheTransactionalRetryPlanner() throws Exception {
        WorkerTaskStore.NodeExecutionResult throttled =
                executor(
                                1024,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                429, new byte[0], "120"))
                        .execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult malformed =
                executor(
                                1024,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                503, new byte[0], "not-a-date"))
                        .execute(task(baseConfig(ENDPOINT)));

        assertEquals(java.time.Duration.ofMinutes(2), throttled.retryAfter());
        assertNull(malformed.retryAfter());
    }

    @Test
    void advertisesOnlyTheHttpNodeType() throws Exception {
        ManagedHttpWorkerNodeExecutor executor = executor(1024, successfulExchange());

        assertTrue(executor.supports("http"));
        assertFalse(executor.supports("start"));
    }

    @Test
    void rejectsInvalidRequestShapesAndEndpointComponents() throws Exception {
        ManagedHttpWorkerNodeExecutor executor = executor(1024, successfulExchange());

        assertError(INVALID_CONFIG, executor, task("{}"));
        assertError(
                "HTTP_METHOD_INVALID",
                executor,
                task(baseConfig(ENDPOINT).replace("GET", "TRACE")));
        assertError(
                INVALID_CONFIG,
                executor,
                task(config("\"pathTemplate\":\"//escape.example.test\"")));
        assertError(
                INVALID_CONFIG,
                executor,
                task(config("\"pathTemplate\":\"https://escape.example.test\"")));
        assertError(INVALID_CONFIG, executor, task(config("\"headers\":[]")));
        assertError(
                INVALID_CONFIG,
                executor,
                task(config("\"headers\":{\"Bad Header\":\"input.request_id\"}")));

        assertEquals(
                ENDPOINT_DENIED,
                executor.execute(task(baseConfig("https://user@api.example.test"))).errorCode());
        assertEquals(
                ENDPOINT_DENIED,
                executor.execute(task(baseConfig("https://api.example.test#fragment")))
                        .errorCode());
        assertEquals(
                ENDPOINT_DENIED,
                executor.execute(task(baseConfig("https://api.example.test:444"))).errorCode());
        assertEquals(
                ENDPOINT_DENIED,
                executor.execute(task(baseConfig("https://other.example.test"))).errorCode());
        assertThrows(IllegalArgumentException.class, () -> executor(0, successfulExchange()));
    }

    @Test
    void rejectsInvalidMappingsInputsAndTimeouts() throws Exception {
        ManagedHttpWorkerNodeExecutor executor = executor(1024, successfulExchange());

        assertEquals(
                INVALID_CONFIG, executor.execute(task(baseConfig(ENDPOINT), "[]")).errorCode());
        assertConfigError(executor, "\"headers\":{\"X-Test\":\"input\"}");
        assertConfigError(executor, "\"headers\":{\"X-Test\":\"input.null_value\"}");
        assertConfigError(executor, "\"bodyExpression\":\"payload\"");
        assertConfigError(executor, "\"bodyExpression\":\"input.bad-segment\"");
        assertConfigError(executor, "\"bodyExpression\":\"input.absent\"");
        assertConfigError(executor, "\"resultExpression\":\"response\"");
        assertConfigError(executor, "\"resultExpression\":\"response.body.absent\"");
        assertConfigError(executor, "\"_timeout\":\"PT0S\"");
        assertConfigError(executor, "\"_timeout\":\"PT-1S\"");
        assertConfigError(executor, "\"_timeout\":\"PT31M\"");
    }

    @Test
    void classifiesTransportInterruptAndStatusBoundaries() throws Exception {
        ManagedHttpWorkerNodeExecutor executor = executor(1024, successfulExchange());
        WorkerTaskStore.NodeExecutionResult success =
                executor.execute(task(baseConfig(ENDPOINT), null));
        WorkerTaskStore.NodeExecutionResult informational =
                executor(
                                1024,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                199, new byte[0]))
                        .execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult timeout =
                executor(
                                1024,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                408, new byte[0]))
                        .execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult throttled =
                executor(
                                1024,
                                (request, policy, maximumBytes) ->
                                        new ManagedHttpWorkerNodeExecutor.Response(
                                                429, new byte[0]))
                        .execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult transportFailure =
                executor(
                                1024,
                                (request, policy, maximumBytes) -> {
                                    throw new IOException("test transport failure");
                                })
                        .execute(task(baseConfig(ENDPOINT)));
        WorkerTaskStore.NodeExecutionResult interrupted =
                executor(
                                1024,
                                (request, policy, maximumBytes) -> {
                                    throw new InterruptedException("test interruption");
                                })
                        .execute(task(baseConfig(ENDPOINT)));

        assertEquals(WorkerTaskStore.NodeOutcome.SUCCEEDED, success.outcome());
        assertEquals("HTTP_DOWNSTREAM_ERROR", informational.errorCode());
        assertFalse(informational.retryable());
        assertTrue(timeout.retryable());
        assertTrue(throttled.retryable());
        assertEquals("HTTP_TRANSPORT_ERROR", transportFailure.errorCode());
        assertTrue(transportFailure.retryable());
        assertEquals("HTTP_INTERRUPTED", interrupted.errorCode());
        assertTrue(interrupted.retryable());
        assertTrue(Thread.interrupted());
    }

    private static ManagedHttpWorkerNodeExecutor executor(
            int maximumBytes, ManagedHttpWorkerNodeExecutor.HttpExchange exchange)
            throws Exception {
        return new ManagedHttpWorkerNodeExecutor(
                new ObjectMapper(),
                Set.of(HOST),
                maximumBytes,
                ignored -> List.of(InetAddress.getByAddress(new byte[] {8, 8, 8, 8})),
                exchange);
    }

    private static ManagedHttpWorkerNodeExecutor.HttpExchange successfulExchange() {
        return (request, policy, maximumBytes) ->
                new ManagedHttpWorkerNodeExecutor.Response(
                        200, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static String baseConfig(String endpoint) {
        return "{\"_managedEndpoint\":\"" + endpoint + "\",\"method\":\"GET\"}";
    }

    private static String config(String field) {
        return "{\"_managedEndpoint\":\"" + ENDPOINT + "\",\"method\":\"GET\"," + field + "}";
    }

    private static void assertConfigError(ManagedHttpWorkerNodeExecutor executor, String field) {
        assertEquals(INVALID_CONFIG, executor.execute(task(config(field))).errorCode());
    }

    private static void assertError(
            String expected,
            ManagedHttpWorkerNodeExecutor executor,
            WorkerTaskStore.ClaimedTask claimedTask) {
        assertEquals(expected, executor.execute(claimedTask).errorCode());
    }

    private static WorkerTaskStore.ClaimedTask task(String config) {
        return task(config, "{\"request_id\":\"request-1\",\"amount\":10,\"null_value\":null}");
    }

    private static WorkerTaskStore.ClaimedTask task(String config, String inputPayload) {
        WorkerTaskStore.TaskReadyEvent event =
                new WorkerTaskStore.TaskReadyEvent(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c701"),
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c702"),
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c703"),
                        "workflow.task.ready",
                        1,
                        "1234567890abcdef1234567890abcdef");
        return new WorkerTaskStore.ClaimedTask(
                event,
                "workflow-worker",
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c704"),
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c705"),
                "http-call",
                "http",
                1,
                inputPayload,
                null,
                "d".repeat(64),
                config,
                "dispatch-token",
                1,
                Instant.now().plusSeconds(60));
    }
}
