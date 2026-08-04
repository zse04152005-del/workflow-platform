package com.workflowplatform.controlplane.runtime;

import java.util.Map;
import java.util.Set;

/** Explicit allow-list for the 45 machine transitions; no enum ordering is used. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class RuntimeTransitionPolicy {

    private static final String STATUS_DISPATCHED = "DISPATCHED";

    private static final Set<String> NODE_SCHEDULE_STATES =
            Set.of("READY", "RETRY_WAIT", "WAITING_TIMER");
    private static final Set<String> NODE_EXECUTION_STATES = Set.of("DISPATCHED", "RUNNING");
    private static final Set<String> ATTEMPT_TERMINAL_FAILURES =
            Set.of("FAILED", "TIMED_OUT", "ABANDONED", "CANCELLED");

    private static final Map<Key, Rule> RULES =
            Map.ofEntries(
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "CREATED",
                            "RUNNING",
                            "start",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "RUNNING",
                            "PAUSE_REQUESTED",
                            "request_pause",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "RUNNING",
                            "CANCEL_REQUESTED",
                            "request_cancel",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "RUNNING",
                            "SUCCEEDED",
                            "complete_success",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "RUNNING",
                            "FAILED",
                            "complete_failure",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "PAUSE_REQUESTED",
                            "PAUSED",
                            "pause",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "PAUSE_REQUESTED",
                            "CANCEL_REQUESTED",
                            "request_cancel",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "PAUSE_REQUESTED",
                            "FAILED",
                            "pause_failure",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "PAUSED",
                            "RUNNING",
                            "resume",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "PAUSED",
                            "CANCEL_REQUESTED",
                            "request_cancel",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.INSTANCE,
                            "CANCEL_REQUESTED",
                            "CANCELLED",
                            "cancel",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "PENDING",
                            "READY",
                            "make_ready",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "PENDING",
                            "CANCELLED",
                            "cancel_pending",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "READY",
                            "DISPATCHED",
                            "dispatch",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "READY",
                            "WAITING_TIMER",
                            "schedule_timer",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "READY",
                            "WAITING_APPROVAL",
                            "wait_approval",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "READY",
                            "CANCELLED",
                            "cancel_ready",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "DISPATCHED",
                            "RUNNING",
                            "claim",
                            RuntimeTransitionActor.WORKER),
                    rule(
                            RuntimeEntityType.NODE,
                            "DISPATCHED",
                            "READY",
                            "lease_expired",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "READY",
                            "lease_expired",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "DISPATCHED",
                            "CANCELLED",
                            "cancel_dispatched",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "SUCCEEDED",
                            "complete_success",
                            RuntimeTransitionActor.WORKER),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "FAILED",
                            "complete_failure",
                            RuntimeTransitionActor.WORKER),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "RETRY_WAIT",
                            "schedule_retry",
                            RuntimeTransitionActor.WORKER,
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "WAITING_TIMER",
                            "wait_timer",
                            RuntimeTransitionActor.WORKER,
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "WAITING_APPROVAL",
                            "wait_approval",
                            RuntimeTransitionActor.WORKER,
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "RUNNING",
                            "CANCELLED",
                            "cancel_running",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "RETRY_WAIT",
                            "READY",
                            "retry_due",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "RETRY_WAIT",
                            "CANCELLED",
                            "cancel_retry",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "WAITING_TIMER",
                            "READY",
                            "timer_due",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "WAITING_TIMER",
                            "CANCELLED",
                            "cancel_timer",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.NODE,
                            "WAITING_APPROVAL",
                            "READY",
                            "approval_resume",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "WAITING_APPROVAL",
                            "SUCCEEDED",
                            "approval_complete",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "WAITING_APPROVAL",
                            "FAILED",
                            "approval_reject",
                            RuntimeTransitionActor.RUNTIME),
                    rule(
                            RuntimeEntityType.NODE,
                            "WAITING_APPROVAL",
                            "CANCELLED",
                            "cancel_approval",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.ATTEMPT,
                            "RUNNING",
                            "SUCCEEDED",
                            "finish_success",
                            RuntimeTransitionActor.WORKER),
                    rule(
                            RuntimeEntityType.ATTEMPT,
                            "RUNNING",
                            "FAILED",
                            "finish_failure",
                            RuntimeTransitionActor.WORKER),
                    rule(
                            RuntimeEntityType.ATTEMPT,
                            "RUNNING",
                            "TIMED_OUT",
                            "timeout",
                            RuntimeTransitionActor.SCHEDULER,
                            RuntimeTransitionActor.WORKER),
                    rule(
                            RuntimeEntityType.ATTEMPT,
                            "RUNNING",
                            "ABANDONED",
                            "abandon",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.ATTEMPT,
                            "RUNNING",
                            "CANCELLED",
                            "cancel",
                            RuntimeTransitionActor.RUNTIME,
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.SCHEDULE,
                            "PENDING",
                            "CLAIMED",
                            "claim_occurrence",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.SCHEDULE,
                            "PENDING",
                            "SKIPPED",
                            "skip_occurrence",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.SCHEDULE,
                            "CLAIMED",
                            "FIRED",
                            "fire_occurrence",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.SCHEDULE,
                            "CLAIMED",
                            "FAILED",
                            "fail_occurrence",
                            RuntimeTransitionActor.SCHEDULER),
                    rule(
                            RuntimeEntityType.SCHEDULE,
                            "CLAIMED",
                            "SKIPPED",
                            "skip_claimed_occurrence",
                            RuntimeTransitionActor.SCHEDULER));

    private RuntimeTransitionPolicy() {}

    static int ruleCount() {
        return RULES.size();
    }

    static String requireAllowed(RuntimeTransitionCommand command) {
        Key key =
                new Key(
                        command.entityType(),
                        command.expectedStatus(),
                        command.targetStatus(),
                        command.command());
        Rule rule = RULES.get(key);
        if (rule == null) {
            throw failure(RuntimeTransitionException.Failure.INVALID_STATE_TRANSITION);
        }
        if (!rule.actors().contains(command.actor())) {
            throw failure(RuntimeTransitionException.Failure.ACTOR_NOT_ALLOWED);
        }
        validateEffects(command);
        return eventType(command);
    }

    private static void validateEffects(RuntimeTransitionCommand command) {
        RuntimeTransitionEffects effects = command.effects();
        RuntimeEntityType entity = command.entityType();
        String target = command.targetStatus();
        if (entity != RuntimeEntityType.NODE && effects.nextScheduleAt() != null) {
            invalidEffects();
        }
        if (entity == RuntimeEntityType.NODE) {
            boolean scheduleRequired = NODE_SCHEDULE_STATES.contains(target);
            if (scheduleRequired != (effects.nextScheduleAt() != null)) {
                invalidEffects();
            }
            if (STATUS_DISPATCHED.equals(target)) {
                if (effects.workerId() == null || effects.leaseExpiresAt() == null) {
                    invalidEffects();
                }
                if (effects.expectedLeaseGeneration() != null) {
                    invalidEffects();
                }
            }
            if (command.actor() == RuntimeTransitionActor.WORKER && !effects.hasWorkerGuard()) {
                invalidEffects();
            }
            if (command.actor() != RuntimeTransitionActor.WORKER
                    && effects.expectedLeaseGeneration() != null) {
                invalidEffects();
            }
            if (NODE_EXECUTION_STATES.contains(command.expectedStatus())
                    && command.actor() == RuntimeTransitionActor.WORKER
                    && !effects.hasWorkerGuard()) {
                invalidEffects();
            }
            boolean outputAllowed = "SUCCEEDED".equals(target);
            if (!outputAllowed && effects.hasOutput()) {
                invalidEffects();
            }
            boolean errorRequired = Set.of("FAILED", "RETRY_WAIT").contains(target);
            if (errorRequired != effects.hasError()) {
                invalidEffects();
            }
            if (effects.workflowInstanceId() != null) {
                invalidEffects();
            }
            return;
        }
        if (entity == RuntimeEntityType.ATTEMPT) {
            if (command.actor() == RuntimeTransitionActor.WORKER && !effects.hasWorkerGuard()) {
                invalidEffects();
            }
            if (effects.nextScheduleAt() != null
                    || effects.leaseExpiresAt() != null
                    || effects.workflowInstanceId() != null) {
                invalidEffects();
            }
            if (!"SUCCEEDED".equals(target) && effects.hasOutput()) {
                invalidEffects();
            }
            boolean failure = ATTEMPT_TERMINAL_FAILURES.contains(target);
            if (failure != (effects.hasError() && effects.errorSummaryRedacted() != null)) {
                invalidEffects();
            }
            return;
        }
        if (entity == RuntimeEntityType.SCHEDULE) {
            boolean fired = "FIRED".equals(target);
            if (fired != (effects.workflowInstanceId() != null)) {
                invalidEffects();
            }
            if (effects.nextScheduleAt() != null
                    || effects.workerId() != null
                    || effects.leaseExpiresAt() != null
                    || effects.expectedLeaseGeneration() != null
                    || effects.hasOutput()
                    || effects.hasError()) {
                invalidEffects();
            }
            return;
        }
        if (effects.nextScheduleAt() != null
                || effects.workerId() != null
                || effects.leaseExpiresAt() != null
                || effects.expectedLeaseGeneration() != null
                || effects.workflowInstanceId() != null
                || effects.hasOutput()
                || effects.hasError()) {
            invalidEffects();
        }
    }

    private static String eventType(RuntimeTransitionCommand command) {
        if (command.entityType() == RuntimeEntityType.NODE
                && "READY".equals(command.targetStatus())) {
            return "workflow.task.ready";
        }
        return "workflow.runtime."
                + command.entityType().wireName()
                + "."
                + command.command().replace('_', '.');
    }

    private static Map.Entry<Key, Rule> rule(
            RuntimeEntityType entity,
            String from,
            String to,
            String command,
            RuntimeTransitionActor... actors) {
        Key key = new Key(entity, from, to, command);
        return Map.entry(key, new Rule(key, Set.of(actors)));
    }

    private static RuntimeTransitionException failure(RuntimeTransitionException.Failure failure) {
        return new RuntimeTransitionException(failure);
    }

    private static void invalidEffects() {
        throw failure(RuntimeTransitionException.Failure.INVALID_EFFECTS);
    }

    private record Key(RuntimeEntityType entity, String from, String to, String command) {}

    private record Rule(Key key, Set<RuntimeTransitionActor> actors) {
        Rule {
            if (key == null || actors.isEmpty()) {
                throw new IllegalArgumentException("transition rule must have identity and actors");
            }
        }
    }
}
