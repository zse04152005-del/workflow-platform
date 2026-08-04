package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class RuntimeTriggerServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "trigger-service-test");

    @Test
    void manualTriggerCalculatesStableDigestAndDelegates() throws Exception {
        RuntimeTriggerStore store =
                command ->
                        new RuntimeTriggerResult(
                                INSTANCE_ID,
                                VERSION_ID,
                                3,
                                "CREATED",
                                false,
                                202,
                                command.requestDigest(),
                                Instant.parse("2026-07-31T00:00:00Z"));
        RuntimeTriggerService service = new RuntimeTriggerService(store, new ObjectMapper());

        RuntimeTriggerResult result =
                service.manual(
                        CONTEXT,
                        WORKFLOW_ID,
                        new ObjectMapper().readTree("{\"orderId\":\"o-1\"}"),
                        "order/o-1",
                        "trigger-key-001",
                        "0123456789abcdef0123456789abcdef");

        assertEquals(INSTANCE_ID, result.instanceId());
        assertEquals(3, result.workflowVersion());
        assertEquals(64, result.requestDigest().length());
        assertNotNull(result.acceptedAt());
    }

    @Test
    void invalidInputAndIdempotencyAreRejectedBeforePersistence() throws Exception {
        RuntimeTriggerService service =
                new RuntimeTriggerService(
                        (command) -> {
                            throw new AssertionError("store must not be called");
                        },
                        new ObjectMapper());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.manual(
                                CONTEXT,
                                WORKFLOW_ID,
                                new ObjectMapper().readTree("[1]"),
                                null,
                                "short",
                                null));
    }

    @Test
    void sameRequestProducesSameDigest() throws Exception {
        java.util.List<String> digests = new java.util.ArrayList<>();
        RuntimeTriggerService service =
                new RuntimeTriggerService(
                        command -> {
                            digests.add(command.requestDigest());
                            return new RuntimeTriggerResult(
                                    INSTANCE_ID,
                                    VERSION_ID,
                                    1,
                                    "CREATED",
                                    false,
                                    202,
                                    command.requestDigest(),
                                    Instant.EPOCH);
                        },
                        new ObjectMapper());
        var input = new ObjectMapper().readTree("{\"a\":1}");
        service.manual(
                CONTEXT,
                WORKFLOW_ID,
                input,
                null,
                "trigger-key-001",
                "0123456789abcdef0123456789abcdef");
        service.manual(
                CONTEXT,
                WORKFLOW_ID,
                input,
                null,
                "trigger-key-001",
                "0123456789abcdef0123456789abcdef");
        assertEquals(digests.get(0), digests.get(1));
    }
}
