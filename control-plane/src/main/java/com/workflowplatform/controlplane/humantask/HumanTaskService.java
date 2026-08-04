package com.workflowplatform.controlplane.humantask;

import com.workflowplatform.controlplane.security.AuditRedactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Human-task lifecycle with first-decision version checks and explicit timeout policy. */
public final class HumanTaskService {

    private final Map<UUID, HumanTask> tasks = new ConcurrentHashMap<>();
    private final AuditRedactor redactor = new AuditRedactor();

    public Creation create(CreateCommand command) {
        HumanTask task =
                new HumanTask(
                        command.taskId(),
                        command.nodeInstanceId(),
                        Status.OPEN,
                        0,
                        Set.copyOf(command.candidateRoles()),
                        command.assignee(),
                        command.dueAt(),
                        command.timeoutPolicy(),
                        redactor.redact(command.form()),
                        List.of());
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new IllegalStateException("human task already exists");
        }
        return new Creation(task, "WAITING_APPROVAL");
    }

    public List<HumanTask> inbox(String user, Set<String> roles, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("inbox limit must be between 1 and 200");
        }
        return tasks.values().stream()
                .filter(task -> task.status() == Status.OPEN)
                .filter(
                        task ->
                                user.equals(task.assignee())
                                        || task.candidateRoles().stream().anyMatch(roles::contains))
                .sorted(Comparator.comparing(HumanTask::dueAt).thenComparing(HumanTask::id))
                .limit(limit)
                .toList();
    }

    public synchronized HumanTask decide(
            UUID id, long expectedVersion, Decision decision, String actor, String reason) {
        HumanTask current = requireOpen(id, expectedVersion);
        Status status = decision == Decision.APPROVE ? Status.APPROVED : Status.REJECTED;
        HumanTask updated = current.transition(status, actor, reason, current.assignee());
        tasks.put(id, updated);
        return updated;
    }

    public synchronized HumanTask transfer(
            UUID id, long expectedVersion, String actor, String assignee, String reason) {
        HumanTask current = requireOpen(id, expectedVersion);
        if (assignee == null || assignee.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("transfer assignee and reason are required");
        }
        HumanTask updated = current.transition(Status.OPEN, actor, reason, assignee);
        tasks.put(id, updated);
        return updated;
    }

    public synchronized TimeoutResult timeout(UUID id, Instant now) {
        HumanTask current = tasks.get(id);
        if (current == null || current.status() != Status.OPEN || current.dueAt().isAfter(now)) {
            return new TimeoutResult(current, TimeoutAction.NONE);
        }
        TimeoutAction action =
                switch (current.timeoutPolicy()) {
                    case FAIL -> TimeoutAction.FAIL_NODE;
                    case AUTO_REJECT -> TimeoutAction.REJECT_NODE;
                    case ESCALATE -> TimeoutAction.ESCALATE_REMINDER;
                };
        Status status =
                current.timeoutPolicy() == TimeoutPolicy.ESCALATE ? Status.OPEN : Status.TIMED_OUT;
        HumanTask updated =
                current.transition(status, "scheduler", "APPROVAL_TIMEOUT", current.assignee());
        tasks.put(id, updated);
        return new TimeoutResult(updated, action);
    }

    private HumanTask requireOpen(UUID id, long expectedVersion) {
        HumanTask current = tasks.get(id);
        if (current == null
                || current.status() != Status.OPEN
                || current.version() != expectedVersion) {
            throw new IllegalStateException("human task decision conflict");
        }
        return current;
    }

    public record CreateCommand(
            UUID taskId,
            UUID nodeInstanceId,
            Set<String> candidateRoles,
            String assignee,
            Instant dueAt,
            TimeoutPolicy timeoutPolicy,
            Map<String, Object> form) {
        public CreateCommand {
            candidateRoles = Set.copyOf(candidateRoles);
            form = Map.copyOf(form);
        }
    }

    public record Creation(HumanTask task, String nodeStatus) {}

    public record HumanTask(
            UUID id,
            UUID nodeInstanceId,
            Status status,
            long version,
            Set<String> candidateRoles,
            String assignee,
            Instant dueAt,
            TimeoutPolicy timeoutPolicy,
            Map<String, Object> formRedacted,
            List<Action> actions) {
        public HumanTask {
            candidateRoles = Set.copyOf(candidateRoles);
            formRedacted = Map.copyOf(formRedacted);
            actions = List.copyOf(actions);
            if (candidateRoles.isEmpty() && (assignee == null || assignee.isBlank())) {
                throw new IllegalArgumentException("candidate role or assignee is required");
            }
        }

        HumanTask transition(Status next, String actor, String reason, String nextAssignee) {
            List<Action> nextActions = new ArrayList<>(actions);
            nextActions.add(new Action(next, actor, reason, Instant.now()));
            return new HumanTask(
                    id,
                    nodeInstanceId,
                    next,
                    version + 1,
                    candidateRoles,
                    nextAssignee,
                    dueAt,
                    timeoutPolicy,
                    formRedacted,
                    nextActions);
        }
    }

    public record Action(Status status, String actor, String reason, Instant occurredAt) {}

    public record TimeoutResult(HumanTask task, TimeoutAction action) {}

    public enum Status {
        OPEN,
        APPROVED,
        REJECTED,
        TIMED_OUT
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public enum TimeoutPolicy {
        FAIL,
        AUTO_REJECT,
        ESCALATE
    }

    public enum TimeoutAction {
        NONE,
        FAIL_NODE,
        REJECT_NODE,
        ESCALATE_REMINDER
    }
}
