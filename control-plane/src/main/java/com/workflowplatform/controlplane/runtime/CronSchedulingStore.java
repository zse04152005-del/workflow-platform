package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-authoritative port for Cron occurrence materialization and firing. */
public interface CronSchedulingStore {

    List<CronOccurrence> materialize(
            WorkflowRequestContext context,
            UUID triggerConfigId,
            Instant now,
            int catchUpLimit,
            boolean allowOverlap);

    List<FiredCronOccurrence> fireDue(
            WorkflowRequestContext context,
            String schedulerId,
            Instant now,
            int limit,
            String traceId);

    record CronOccurrence(UUID id, UUID triggerConfigId, Instant scheduledAt, String status) {}

    record FiredCronOccurrence(UUID occurrenceId, UUID workflowInstanceId, Instant scheduledAt) {}
}
