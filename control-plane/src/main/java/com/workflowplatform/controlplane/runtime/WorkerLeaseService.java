package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lease/generation/fencing protocol used by workers before claim, heartbeat and result writes. */
public final class WorkerLeaseService {

    private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();

    public synchronized Lease claim(
            UUID taskId, String workerId, Instant now, long durationSeconds) {
        if (taskId == null
                || workerId == null
                || workerId.isBlank()
                || now == null
                || durationSeconds < 1) {
            throw new IllegalArgumentException("invalid worker claim");
        }
        Lease current = leases.get(taskId);
        if (current != null && current.expiresAt().isAfter(now)) {
            throw new IllegalStateException("task lease is active");
        }
        long generation = current == null ? 1 : current.generation() + 1;
        Lease next = new Lease(taskId, workerId, generation, now.plusSeconds(durationSeconds));
        leases.put(taskId, next);
        return next;
    }

    public synchronized Lease heartbeat(LeaseToken token, Instant now, long durationSeconds) {
        Lease current = requireCurrent(token, now);
        Lease refreshed =
                new Lease(
                        current.taskId(),
                        current.workerId(),
                        current.generation(),
                        now.plusSeconds(durationSeconds));
        leases.put(current.taskId(), refreshed);
        return refreshed;
    }

    public synchronized void requireFence(LeaseToken token, Instant now) {
        requireCurrent(token, now);
    }

    public synchronized void release(LeaseToken token) {
        Lease current = leases.get(token.taskId());
        if (current != null
                && current.generation() == token.generation()
                && current.workerId().equals(token.workerId())) {
            leases.remove(token.taskId());
        }
    }

    private Lease requireCurrent(LeaseToken token, Instant now) {
        Objects.requireNonNull(token, "token");
        Lease current = leases.get(token.taskId());
        if (current == null
                || !current.workerId().equals(token.workerId())
                || current.generation() != token.generation()
                || !current.expiresAt().isAfter(now)) {
            throw new IllegalStateException("worker fencing token is stale or expired");
        }
        return current;
    }

    public record Lease(UUID taskId, String workerId, long generation, Instant expiresAt) {
        public Lease {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        public LeaseToken token() {
            return new LeaseToken(taskId, workerId, generation);
        }
    }

    public record LeaseToken(UUID taskId, String workerId, long generation) {}
}
