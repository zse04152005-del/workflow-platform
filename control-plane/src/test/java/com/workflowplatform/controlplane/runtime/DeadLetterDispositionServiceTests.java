package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workflowplatform.controlplane.runtime.DeadLetterDispositionStore.DeadLetterAction;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionStore.DeadLetterDispositionCommand;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class DeadLetterDispositionServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID DEAD_LETTER_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "dead-letter-test");

    @Test
    void redactsAndFingerprintsAStableOperatorNote() {
        AtomicReference<DeadLetterDispositionCommand> captured = new AtomicReference<>();
        DeadLetterDispositionService service = service(captured);

        service.addNote(
                CONTEXT,
                DEAD_LETTER_ID,
                "  password=do-not-store investigate timeout  ",
                "dead-note-key-001",
                "00-" + TRACE_ID + "-0123456789abcdef-01");
        DeadLetterDispositionCommand first = captured.get();
        service.addNote(
                CONTEXT,
                DEAD_LETTER_ID,
                "password=do-not-store investigate timeout",
                "dead-note-key-001",
                TRACE_ID);
        DeadLetterDispositionCommand second = captured.get();

        assertEquals(DeadLetterAction.NOTE, first.action());
        assertEquals(TRACE_ID, first.traceId());
        assertEquals("password=<redacted> investigate timeout", first.noteRedacted());
        assertFalse(first.noteRedacted().contains("do-not-store"));
        assertEquals("runtime:dead-letter:note:" + DEAD_LETTER_ID, first.operationScope());
        assertEquals(first.requestDigest(), second.requestDigest());
        assertNotEquals(first.actionId(), second.actionId());
    }

    @Test
    void distinguishesRedispatchAndRejectsUnsafeBoundaries() {
        AtomicReference<DeadLetterDispositionCommand> captured = new AtomicReference<>();
        DeadLetterDispositionService service = service(captured);

        service.redispatch(
                CONTEXT, DEAD_LETTER_ID, "operator approved retry", "dead-requeue-001", null);
        assertEquals(DeadLetterAction.REDISPATCH, captured.get().action());
        assertEquals(32, captured.get().traceId().length());

        DeadLetterDispositionService rejecting =
                new DeadLetterDispositionService(
                        command -> {
                            throw new AssertionError("store must not be called");
                        });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        rejecting.addNote(
                                CONTEXT, DEAD_LETTER_ID, " ", "dead-note-key-001", TRACE_ID));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        rejecting.redispatch(
                                CONTEXT,
                                DEAD_LETTER_ID,
                                "retry",
                                "short",
                                "00-00000000000000000000000000000000-0123456789abcdef-01"));
    }

    private static DeadLetterDispositionService service(
            AtomicReference<DeadLetterDispositionCommand> captured) {
        return new DeadLetterDispositionService(
                command -> {
                    captured.set(command);
                    return new DeadLetterDispositionResult(
                            command.actionId(),
                            Instant.EPOCH,
                            false,
                            command.deadLetterId(),
                            "OPEN",
                            null);
                });
    }
}
