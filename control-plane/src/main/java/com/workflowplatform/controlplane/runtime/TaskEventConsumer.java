package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Kafka-compatible consumer boundary with processed-event deduplication. */
public final class TaskEventConsumer {

    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();
    private final Handler handler;

    public TaskEventConsumer(Handler eventHandler) {
        handler = Objects.requireNonNull(eventHandler, "eventHandler");
    }

    public ConsumeResult consume(TaskEvent event) {
        if (!processed.add(event.eventId())) {
            return ConsumeResult.DUPLICATE;
        }
        try {
            handler.handle(event);
            return ConsumeResult.PROCESSED;
        } catch (RuntimeException exception) {
            processed.remove(event.eventId());
            return ConsumeResult.RETRY;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(TaskEvent event);
    }

    public record TaskEvent(
            UUID eventId, UUID tenantId, UUID taskId, String payload, Instant occurredAt) {
        public TaskEvent {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public enum ConsumeResult {
        PROCESSED,
        DUPLICATE,
        RETRY
    }
}
