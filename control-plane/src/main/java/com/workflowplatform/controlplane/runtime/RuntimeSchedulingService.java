package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Public scheduling boundary for persistent DAG progress and concurrent-safe due dispatch. */
@Service
public final class RuntimeSchedulingService {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SCHEDULER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final int MAXIMUM_BATCH_SIZE = 500;

    private final RuntimeSchedulingStore store;

    public RuntimeSchedulingService(RuntimeSchedulingStore schedulingStore) {
        store = Objects.requireNonNull(schedulingStore, "schedulingStore");
    }

    public RuntimeSchedulingStore.ProgressResult progress(
            WorkflowRequestContext context, UUID instanceId, String traceId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(instanceId, "instanceId");
        requireTraceId(traceId);
        return store.progress(context, instanceId, traceId);
    }

    public List<RuntimeSchedulingStore.DispatchResult> dispatchDue(
            WorkflowRequestContext context,
            String schedulerId,
            Instant dueAt,
            int limit,
            Duration leaseDuration,
            String traceId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        requireTraceId(traceId);
        if (schedulerId == null || !SCHEDULER_ID.matcher(schedulerId).matches()) {
            throw new IllegalArgumentException("schedulerId has an invalid stable format");
        }
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("dispatch limit must be between 1 and 500");
        }
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException(
                    "lease duration must be between 1 ns and 30 minutes");
        }
        return store.dispatchDue(context, schedulerId, dueAt, limit, leaseDuration, traceId);
    }

    private static void requireTraceId(String traceId) {
        if (traceId == null
                || !TRACE_ID.matcher(traceId).matches()
                || traceId.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException("traceId must be a non-zero W3C trace id");
        }
    }
}
