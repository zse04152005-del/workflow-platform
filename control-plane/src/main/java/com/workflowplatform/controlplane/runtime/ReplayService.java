package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import java.util.List;
import java.util.UUID;

/** Builds immutable replay plans without modifying prior attempts. */
public final class ReplayService {

    public ReplayPlan fromFailedNode(UUID instanceId, UUID nodeId, List<UUID> historicalAttempts) {
        return new ReplayPlan(
                ReplayMode.FAILED_NODE,
                instanceId,
                instanceId,
                nodeId,
                List.copyOf(historicalAttempts));
    }

    public ReplayPlan asNewInstance(UUID instanceId, List<UUID> historicalAttempts) {
        return new ReplayPlan(
                ReplayMode.NEW_INSTANCE,
                instanceId,
                TimeOrderedUuidV7.generate(),
                null,
                List.copyOf(historicalAttempts));
    }

    public record ReplayPlan(
            ReplayMode mode,
            UUID sourceInstanceId,
            UUID targetInstanceId,
            UUID startNodeId,
            List<UUID> historicalAttemptIds) {
        public ReplayPlan {
            historicalAttemptIds = List.copyOf(historicalAttemptIds);
        }
    }

    public enum ReplayMode {
        FAILED_NODE,
        NEW_INSTANCE
    }
}
