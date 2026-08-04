package com.workflowplatform.controlplane.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** At-least-once Outbox publisher contract with idempotent event sink and bounded retries. */
public final class OutboxPublisher {

    private final EventSink sink;
    private final Set<UUID> sent = ConcurrentHashMap.newKeySet();

    public OutboxPublisher(EventSink eventSink) {
        sink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public List<PublishResult> publish(List<OutboxRecord> records, Instant now) {
        return records.stream().map(record -> publishOne(record, now)).toList();
    }

    private PublishResult publishOne(OutboxRecord record, Instant now) {
        if (record.nextPublishAt().isAfter(now)) {
            return new PublishResult(record.id(), PublishState.DEFERRED, record.attempts());
        }
        if (sent.contains(record.id())) {
            return new PublishResult(record.id(), PublishState.DUPLICATE, record.attempts());
        }
        try {
            sink.send(record);
            sent.add(record.id());
            return new PublishResult(record.id(), PublishState.SENT, record.attempts() + 1);
        } catch (RuntimeException exception) {
            return new PublishResult(record.id(), PublishState.RETRY, record.attempts() + 1);
        }
    }

    @FunctionalInterface
    public interface EventSink {
        void send(OutboxRecord record);
    }

    public record OutboxRecord(
            UUID id, String eventType, String payload, Instant nextPublishAt, int attempts) {
        public OutboxRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(nextPublishAt, "nextPublishAt");
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts cannot be negative");
            }
        }

        public Instant retryAt(Instant now) {
            long seconds = Math.min(3600L, 1L << Math.min(attempts, 10));
            return now.plus(Duration.ofSeconds(seconds));
        }
    }

    public record PublishResult(UUID id, PublishState state, int attempts) {}

    public enum PublishState {
        SENT,
        DUPLICATE,
        RETRY,
        DEFERRED
    }
}
