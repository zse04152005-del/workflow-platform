package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable claim and completion port for transactional Outbox publication. */
public interface OutboxPublicationStore {

    List<ClaimedEvent> claim(
            WorkflowRequestContext context,
            String publisherId,
            Instant dueAt,
            int limit,
            Duration leaseDuration);

    void markPublished(
            WorkflowRequestContext context, UUID eventId, String publisherId, long expectedVersion);

    void releaseForRetry(
            WorkflowRequestContext context,
            UUID eventId,
            String publisherId,
            long expectedVersion,
            Instant retryAt);

    record ClaimedEvent(
            UUID eventId,
            String eventType,
            String partitionKey,
            String payload,
            String traceContext,
            int publishAttempts,
            long version) {}
}
