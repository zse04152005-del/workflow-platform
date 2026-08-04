package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** At-least-once publisher that durably records success or a bounded retry schedule. */
@Service
public final class OutboxPublicationService {

    private static final Pattern PUBLISHER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,160}");

    private final OutboxPublicationStore store;

    public OutboxPublicationService(OutboxPublicationStore publicationStore) {
        store = Objects.requireNonNull(publicationStore, "publicationStore");
    }

    public List<PublicationResult> publishBatch(
            WorkflowRequestContext context,
            String publisherId,
            Instant dueAt,
            int limit,
            Duration leaseDuration,
            EventSink eventSink) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(eventSink, "eventSink");
        if (publisherId == null || !PUBLISHER_ID.matcher(publisherId).matches()) {
            throw new IllegalArgumentException("publisherId has an invalid stable format");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "Outbox publication limit must be between 1 and 500");
        }
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException(
                    "Outbox lease must be positive and at most 30 minutes");
        }
        List<OutboxPublicationStore.ClaimedEvent> claimed =
                store.claim(context, publisherId, dueAt, limit, leaseDuration);
        List<PublicationResult> results = new ArrayList<>();
        for (OutboxPublicationStore.ClaimedEvent event : claimed) {
            try {
                eventSink.publish(event);
                store.markPublished(context, event.eventId(), publisherId, event.version());
                results.add(new PublicationResult(event.eventId(), PublicationState.PUBLISHED));
            } catch (RuntimeException exception) {
                Instant retryAt = dueAt.plus(retryDelay(event.publishAttempts()));
                store.releaseForRetry(
                        context, event.eventId(), publisherId, event.version(), retryAt);
                results.add(new PublicationResult(event.eventId(), PublicationState.RETRY));
            }
        }
        return List.copyOf(results);
    }

    private static Duration retryDelay(int attempts) {
        long seconds = Math.min(3600L, 1L << Math.min(attempts, 11));
        return Duration.ofSeconds(seconds);
    }

    @FunctionalInterface
    public interface EventSink {
        void publish(OutboxPublicationStore.ClaimedEvent event);
    }

    public record PublicationResult(UUID eventId, PublicationState state) {}

    public enum PublicationState {
        PUBLISHED,
        RETRY
    }
}
