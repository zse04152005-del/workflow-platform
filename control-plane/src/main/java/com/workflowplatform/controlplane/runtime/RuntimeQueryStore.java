package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped persistence port for stable runtime history queries. */
public interface RuntimeQueryStore {

    Optional<InstanceView> findInstance(WorkflowRequestContext context, UUID instanceId);

    List<InstanceView> instances(
            WorkflowRequestContext context, CursorBoundary boundary, int fetchLimit);

    List<NodeView> nodes(
            WorkflowRequestContext context,
            UUID instanceId,
            CursorBoundary boundary,
            int fetchLimit);

    List<AttemptView> attempts(
            WorkflowRequestContext context,
            UUID instanceId,
            CursorBoundary boundary,
            int fetchLimit);

    interface CursorItem {
        UUID id();

        Instant createdAt();
    }

    record CursorBoundary(Instant createdAt, UUID id) {}

    record InstanceView(
            UUID id,
            UUID workflowId,
            UUID workflowVersionId,
            int workflowVersion,
            String status,
            String businessKey,
            String inputReference,
            String inputDigest,
            String traceId,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt)
            implements CursorItem {}

    record NodeView(
            UUID id,
            UUID instanceId,
            String nodeId,
            String nodeType,
            String status,
            int attemptNo,
            String inputReference,
            String inputDigest,
            String outputReference,
            String outputDigest,
            String errorCode,
            Instant nextScheduleAt,
            Instant createdAt)
            implements CursorItem {}

    record AttemptView(
            UUID id,
            UUID instanceId,
            UUID nodeInstanceId,
            int attemptNo,
            UUID executionId,
            String status,
            String inputReference,
            String inputDigest,
            String outputReference,
            String outputDigest,
            String errorCode,
            String errorSummaryRedacted,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt)
            implements CursorItem {}
}
