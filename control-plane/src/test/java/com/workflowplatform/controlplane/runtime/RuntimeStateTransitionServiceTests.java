package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.AvoidDuplicateLiterals"})
class RuntimeStateTransitionServiceTests {

    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(
                    UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493"), "runtime-test");
    private static final UUID ENTITY_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void policyContainsEveryExplicitMachineTransition() {
        assertEquals(45, RuntimeTransitionPolicy.ruleCount());
    }

    @Test
    void validTransitionDelegatesWithStableEventType() {
        RuntimeTransitionStore store =
                (command, eventType) ->
                        new RuntimeTransitionResult(
                                command.entityType(),
                                command.entityId(),
                                command.command(),
                                command.expectedStatus(),
                                command.targetStatus(),
                                command.expectedVersion() + 1,
                                ENTITY_ID,
                                Instant.parse("2026-07-31T00:00:00Z"));

        RuntimeTransitionResult result =
                new RuntimeStateTransitionService(
                                (command, eventType) -> {
                                    assertEquals("workflow.runtime.instance.start", eventType);
                                    return store.transition(command, eventType);
                                })
                        .transition(
                                command(
                                        RuntimeEntityType.INSTANCE,
                                        "CREATED",
                                        "RUNNING",
                                        "start",
                                        RuntimeTransitionActor.RUNTIME,
                                        RuntimeTransitionEffects.none()));

        assertEquals("RUNNING", result.currentStatus());
        assertEquals(1, result.version());
    }

    @Test
    void illegalTransitionAndActorAreRejectedBeforePersistence() {
        RuntimeStateTransitionService service =
                new RuntimeStateTransitionService(
                        (command, eventType) -> {
                            throw new AssertionError("persistence must not be called");
                        });

        RuntimeTransitionException illegal =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                service.transition(
                                        command(
                                                RuntimeEntityType.INSTANCE,
                                                "CREATED",
                                                "SUCCEEDED",
                                                "start",
                                                RuntimeTransitionActor.RUNTIME,
                                                RuntimeTransitionEffects.none())));
        assertEquals(
                RuntimeTransitionException.Failure.INVALID_STATE_TRANSITION, illegal.failure());

        RuntimeTransitionException actor =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                service.transition(
                                        command(
                                                RuntimeEntityType.INSTANCE,
                                                "CREATED",
                                                "RUNNING",
                                                "start",
                                                RuntimeTransitionActor.WORKER,
                                                RuntimeTransitionEffects.none())));
        assertEquals(RuntimeTransitionException.Failure.ACTOR_NOT_ALLOWED, actor.failure());
    }

    @Test
    void stateSpecificEffectsAndWorkerGuardAreRequired() {
        RuntimeStateTransitionService service =
                new RuntimeStateTransitionService(
                        (command, eventType) -> {
                            throw new AssertionError("persistence must not be called");
                        });
        RuntimeTransitionException schedule =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                service.transition(
                                        command(
                                                RuntimeEntityType.NODE,
                                                "PENDING",
                                                "READY",
                                                "make_ready",
                                                RuntimeTransitionActor.RUNTIME,
                                                RuntimeTransitionEffects.none())));
        assertEquals(RuntimeTransitionException.Failure.INVALID_EFFECTS, schedule.failure());

        RuntimeTransitionEffects dispatch =
                new RuntimeTransitionEffects(
                        null,
                        "worker-a",
                        Instant.parse("2026-07-31T00:01:00Z"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        RuntimeTransitionException guard =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                service.transition(
                                        command(
                                                RuntimeEntityType.NODE,
                                                "DISPATCHED",
                                                "RUNNING",
                                                "claim",
                                                RuntimeTransitionActor.WORKER,
                                                dispatch)));
        assertEquals(RuntimeTransitionException.Failure.INVALID_EFFECTS, guard.failure());
    }

    private static RuntimeTransitionCommand command(
            RuntimeEntityType entity,
            String from,
            String to,
            String command,
            RuntimeTransitionActor actor,
            RuntimeTransitionEffects effects) {
        return new RuntimeTransitionCommand(
                CONTEXT,
                entity,
                ENTITY_ID,
                command,
                from,
                0,
                to,
                actor,
                "TEST_TRANSITION",
                TRACE_ID,
                effects);
    }
}
