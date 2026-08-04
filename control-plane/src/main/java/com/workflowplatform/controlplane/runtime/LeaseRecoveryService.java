package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Selects expired worker leases for safe generation-incremented redispatch. */
@Service
public final class LeaseRecoveryService {

    private static final int MAXIMUM_BATCH_SIZE = 500;
    private static final Pattern SCHEDULER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    private final Optional<RuntimeSchedulingStore> store;

    public LeaseRecoveryService() {
        store = Optional.empty();
    }

    @Autowired
    public LeaseRecoveryService(RuntimeSchedulingStore schedulingStore) {
        store = Optional.of(Objects.requireNonNull(schedulingStore, "schedulingStore"));
    }

    public List<RuntimeSchedulingStore.LeaseRecoveryResult> recoverExpired(
            WorkflowRequestContext context,
            String schedulerId,
            Instant expiredAt,
            int limit,
            String traceId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expiredAt, "expiredAt");
        if (schedulerId == null || !SCHEDULER_ID.matcher(schedulerId).matches()) {
            throw new IllegalArgumentException("schedulerId has an invalid stable format");
        }
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("recovery limit must be between 1 and 500");
        }
        if (traceId == null
                || !TRACE_ID.matcher(traceId).matches()
                || traceId.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException("traceId must be a non-zero W3C trace id");
        }
        return store.orElseThrow(
                        () -> new IllegalStateException("persistent lease recovery is unavailable"))
                .recoverExpired(context, schedulerId, expiredAt, limit, traceId);
    }

    public List<Recovery> recover(List<LeaseRow> rows, Instant now, int limit) {
        if (now == null || limit < 1) {
            throw new IllegalArgumentException("recovery time and limit are required");
        }
        return rows.stream()
                .filter(row -> row.expiresAt().compareTo(now) <= 0)
                .sorted(Comparator.comparing(LeaseRow::expiresAt).thenComparing(LeaseRow::taskId))
                .limit(limit)
                .map(
                        row ->
                                new Recovery(
                                        row.taskId(),
                                        row.dispatchCount() + 1,
                                        row.attemptNo(),
                                        row.leaseGeneration() + 1))
                .toList();
    }

    public record LeaseRow(
            UUID taskId,
            Instant expiresAt,
            int dispatchCount,
            int attemptNo,
            long leaseGeneration) {}

    public record Recovery(
            UUID taskId, int dispatchCount, int attemptNo, long nextLeaseGeneration) {}
}
