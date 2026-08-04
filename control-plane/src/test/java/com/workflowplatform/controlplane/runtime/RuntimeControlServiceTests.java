package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlAction;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlCommand;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class RuntimeControlServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String PAUSE_KEY = "pause-key-001";
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "runtime-control-test");

    @Test
    void normalizesAndHashesAStablePauseCommand() {
        AtomicReference<RuntimeControlCommand> captured = new AtomicReference<>();
        RuntimeControlService service = service(captured);

        service.control(
                CONTEXT,
                INSTANCE_ID,
                RuntimeControlAction.PAUSE,
                "  planned maintenance  ",
                null,
                PAUSE_KEY,
                "00-" + TRACE_ID + "-0123456789abcdef-01");
        RuntimeControlCommand first = captured.get();
        service.control(
                CONTEXT,
                INSTANCE_ID,
                RuntimeControlAction.PAUSE,
                "planned maintenance",
                null,
                PAUSE_KEY,
                TRACE_ID);
        RuntimeControlCommand second = captured.get();

        assertEquals("planned maintenance", first.reason());
        assertEquals(TRACE_ID, first.traceId());
        assertEquals("runtime:control:pause:" + INSTANCE_ID, first.operationScope());
        assertEquals(64, first.keyDigest().length());
        assertEquals(first.requestDigest(), second.requestDigest());
        assertNotEquals(first.idempotencyRecordId(), second.idempotencyRecordId());
    }

    @Test
    void acceptsAStableReplayNodeAndIncludesItInTheDigest() {
        AtomicReference<RuntimeControlCommand> captured = new AtomicReference<>();
        RuntimeControlService service = service(captured);

        service.control(
                CONTEXT,
                INSTANCE_ID,
                RuntimeControlAction.REPLAY,
                "retry failed branch",
                "failed_node",
                "replay-key-001",
                null);

        assertEquals("failed_node", captured.get().fromNodeId());
        assertEquals(RuntimeControlAction.REPLAY, captured.get().action());
        assertEquals(32, captured.get().traceId().length());
    }

    @Test
    void rejectsUnsafeInputsBeforePersistence() {
        RuntimeControlService service =
                new RuntimeControlService(
                        command -> {
                            throw new AssertionError("store must not be called");
                        });

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.control(
                                CONTEXT,
                                INSTANCE_ID,
                                RuntimeControlAction.PAUSE,
                                "unsafe\nreason",
                                null,
                                PAUSE_KEY,
                                TRACE_ID));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.control(
                                CONTEXT,
                                INSTANCE_ID,
                                RuntimeControlAction.PAUSE,
                                "maintenance",
                                "node_a",
                                PAUSE_KEY,
                                TRACE_ID));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.control(
                                CONTEXT,
                                INSTANCE_ID,
                                RuntimeControlAction.REPLAY,
                                "retry",
                                "Invalid-Node",
                                "short",
                                "00-00000000000000000000000000000000-0123456789abcdef-01"));
    }

    private static RuntimeControlService service(AtomicReference<RuntimeControlCommand> captured) {
        return new RuntimeControlService(
                command -> {
                    captured.set(command);
                    return new RuntimeControlResult(
                            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495"),
                            Instant.EPOCH,
                            false,
                            INSTANCE_ID,
                            "PAUSED");
                });
    }
}
