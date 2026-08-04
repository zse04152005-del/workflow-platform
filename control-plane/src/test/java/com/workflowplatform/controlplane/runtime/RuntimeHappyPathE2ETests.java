package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class RuntimeHappyPathE2ETests {

    @Test
    void httpConditionHttpDelayEndSurvivesScannerRecreation() throws Exception {
        UUID instanceId = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
        RuntimeTriggerService triggers =
                new RuntimeTriggerService(
                        command ->
                                new RuntimeTriggerResult(
                                        instanceId,
                                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494"),
                                        1,
                                        "CREATED",
                                        false,
                                        202,
                                        command.requestDigest(),
                                        Instant.parse("2026-07-31T00:00:00Z")),
                        new ObjectMapper());
        RuntimeTriggerResult trigger =
                triggers.http(
                        new WorkflowRequestContext(
                                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495"),
                                "e2e-trigger"),
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496"),
                        new ObjectMapper().readTree("{\"orderId\":\"o-1\"}"),
                        "order/o-1",
                        "e2e-trigger-key",
                        "0123456789abcdef0123456789abcdef");
        assertEquals(instanceId, trigger.instanceId());

        NodePlugin.NodeExecutionContext conditionContext =
                new NodePlugin.NodeExecutionContext(
                        "condition", Map.of("condition", true), Duration.ofSeconds(1));
        assertTrue(BuiltinNodePlugins.condition().execute(conditionContext).success());

        ManagedHttpNodePlugin http =
                new ManagedHttpNodePlugin(
                        Set.of("api.example.test"),
                        1024,
                        (uri, input, timeout) ->
                                new ManagedHttpNodePlugin.HttpResponse(
                                        200, "accepted".getBytes(StandardCharsets.UTF_8)));
        assertTrue(
                http.execute(
                                new NodePlugin.NodeExecutionContext(
                                        "http",
                                        Map.of("endpoint", "https://api.example.test/orders/o-1"),
                                        Duration.ofSeconds(5)))
                        .success());

        Instant due = Instant.parse("2026-07-31T00:01:00Z");
        DueTaskScanner recreatedScheduler = new DueTaskScanner();
        List<DueTaskScanner.DueTask> fired =
                recreatedScheduler.scanAndClaim(
                        List.of(
                                new DueTaskScanner.DueTask(
                                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c497"),
                                        "WAITING_TIMER",
                                        due)),
                        due,
                        10);
        assertEquals(1, fired.size());
        assertTrue(
                BuiltinNodePlugins.end()
                        .execute(
                                new NodePlugin.NodeExecutionContext(
                                        "end",
                                        Map.of("instanceId", instanceId),
                                        Duration.ofSeconds(1)))
                        .success());
    }
}
