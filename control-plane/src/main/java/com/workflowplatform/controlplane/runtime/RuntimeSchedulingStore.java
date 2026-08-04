package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transactional persistence port for DAG progress and due-task dispatch. */
public interface RuntimeSchedulingStore {

    ProgressResult progress(WorkflowRequestContext context, UUID instanceId, String traceId);

    List<DispatchResult> dispatchDue(
            WorkflowRequestContext context,
            String schedulerId,
            Instant dueAt,
            int limit,
            Duration leaseDuration,
            String traceId);

    List<LeaseRecoveryResult> recoverExpired(
            WorkflowRequestContext context,
            String schedulerId,
            Instant expiredAt,
            int limit,
            String traceId);

    record ProgressResult(UUID instanceId, boolean instanceStarted, int ready, int cancelled) {}

    record DispatchResult(
            UUID nodeInstanceId,
            String dispatchToken,
            long leaseGeneration,
            Instant leaseExpiresAt) {}

    record LeaseRecoveryResult(
            UUID nodeInstanceId,
            String previousStatus,
            boolean abandonedAttempt,
            int dispatchCount,
            int attemptNo,
            long nextLeaseGeneration) {}
}
