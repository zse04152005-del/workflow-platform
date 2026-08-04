package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Validated tenant-scoped entry point for persistent Cron scheduling. */
@Service
public final class CronSchedulingService {

    private static final int MAXIMUM_BATCH_SIZE = 500;
    private static final Pattern SCHEDULER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private final CronSchedulingStore store;

    public CronSchedulingService(CronSchedulingStore schedulingStore) {
        store = Objects.requireNonNull(schedulingStore, "schedulingStore");
    }

    public List<CronSchedulingStore.CronOccurrence> materialize(
            WorkflowRequestContext context,
            UUID triggerConfigId,
            Instant now,
            int catchUpLimit,
            boolean allowOverlap) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(triggerConfigId, "triggerConfigId");
        Objects.requireNonNull(now, "now");
        if (catchUpLimit < 1 || catchUpLimit > 100) {
            throw new IllegalArgumentException("Cron catch-up limit must be between 1 and 100");
        }
        return store.materialize(context, triggerConfigId, now, catchUpLimit, allowOverlap);
    }

    public List<CronSchedulingStore.FiredCronOccurrence> fireDue(
            WorkflowRequestContext context,
            String schedulerId,
            Instant now,
            int limit,
            String traceId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(now, "now");
        if (schedulerId == null || !SCHEDULER_ID.matcher(schedulerId).matches()) {
            throw new IllegalArgumentException("Cron scheduler id is invalid");
        }
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("Cron fire limit must be between 1 and 500");
        }
        if (traceId == null
                || !TRACE_ID.matcher(traceId).matches()
                || traceId.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException("Cron trace id must be a non-zero W3C trace id");
        }
        return store.fireDue(context, schedulerId, now, limit, traceId);
    }
}
