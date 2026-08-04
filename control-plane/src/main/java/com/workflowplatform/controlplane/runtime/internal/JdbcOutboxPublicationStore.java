package com.workflowplatform.controlplane.runtime.internal;

import com.workflowplatform.controlplane.runtime.OutboxPublicationStore;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL Outbox store with leased SKIP LOCKED claims and monotonic retries. */
@Component
final class JdbcOutboxPublicationStore implements OutboxPublicationStore {

    private static final int ONE_ROW = 1;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcOutboxPublicationStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public List<ClaimedEvent> claim(
            WorkflowRequestContext context,
            String publisherId,
            Instant dueAt,
            int limit,
            Duration leaseDuration) {
        List<ClaimedEvent> claimed =
                transactions.execute(
                        status ->
                                claimInTransaction(
                                        context, publisherId, dueAt, limit, leaseDuration));
        if (claimed == null) {
            throw new IllegalStateException("Outbox claim transaction returned no result");
        }
        return List.copyOf(claimed);
    }

    @Override
    public void markPublished(
            WorkflowRequestContext context,
            UUID eventId,
            String publisherId,
            long expectedVersion) {
        transactions.executeWithoutResult(
                status -> {
                    JdbcRuntimeSupport.setTenantContext(jdbc, context);
                    int changed =
                            jdbc.update(
                                    "UPDATE public.outbox_event SET published_at = transaction_timestamp(), "
                                            + "publisher_id = NULL, publish_lease_until = NULL, "
                                            + "version = version + 1 WHERE tenant_id = ? AND id = ? "
                                            + "AND published_at IS NULL AND publisher_id = ? AND version = ? "
                                            + "AND publish_lease_until > transaction_timestamp()",
                                    context.tenantId(),
                                    eventId,
                                    publisherId,
                                    expectedVersion);
                    requireOne(changed, "Outbox publish completion");
                });
    }

    @Override
    public void releaseForRetry(
            WorkflowRequestContext context,
            UUID eventId,
            String publisherId,
            long expectedVersion,
            Instant retryAt) {
        transactions.executeWithoutResult(
                status -> {
                    JdbcRuntimeSupport.setTenantContext(jdbc, context);
                    int changed =
                            jdbc.update(
                                    "UPDATE public.outbox_event SET next_publish_at = "
                                            + "GREATEST(next_publish_at, ?), publisher_id = NULL, "
                                            + "publish_lease_until = NULL, version = version + 1 "
                                            + "WHERE tenant_id = ? AND id = ? AND published_at IS NULL "
                                            + "AND publisher_id = ? AND version = ?",
                                    atUtc(retryAt),
                                    context.tenantId(),
                                    eventId,
                                    publisherId,
                                    expectedVersion);
                    requireOne(changed, "Outbox retry release");
                });
    }

    private List<ClaimedEvent> claimInTransaction(
            WorkflowRequestContext context,
            String publisherId,
            Instant dueAt,
            int limit,
            Duration leaseDuration) {
        JdbcRuntimeSupport.setTenantContext(jdbc, context);
        List<OutboxRow> due =
                jdbc.query(
                        "SELECT id, event_type, partition_key, payload::text, trace_context, "
                                + "publish_attempts, version FROM public.outbox_event "
                                + "WHERE tenant_id = ? AND published_at IS NULL "
                                + "AND next_publish_at <= ? AND (publisher_id IS NULL "
                                + "OR publish_lease_until <= transaction_timestamp()) "
                                + "ORDER BY next_publish_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                        (result, rowNumber) ->
                                new OutboxRow(
                                        result.getObject("id", UUID.class),
                                        result.getString("event_type"),
                                        result.getString("partition_key"),
                                        result.getString("payload"),
                                        result.getString("trace_context"),
                                        result.getInt("publish_attempts"),
                                        result.getLong("version")),
                        context.tenantId(),
                        atUtc(dueAt),
                        limit);
        Instant leaseUntil = JdbcRuntimeSupport.transactionTimestamp(jdbc).plus(leaseDuration);
        List<ClaimedEvent> claimed = new ArrayList<>();
        for (OutboxRow row : due) {
            int changed =
                    jdbc.update(
                            "UPDATE public.outbox_event SET publisher_id = ?, publish_lease_until = ?, "
                                    + "publish_attempts = publish_attempts + 1, version = version + 1 "
                                    + "WHERE tenant_id = ? AND id = ? AND version = ? "
                                    + "AND published_at IS NULL",
                            publisherId,
                            atUtc(leaseUntil),
                            context.tenantId(),
                            row.id(),
                            row.version());
            requireOne(changed, "Outbox claim");
            claimed.add(
                    new ClaimedEvent(
                            row.id(),
                            row.eventType(),
                            row.partitionKey(),
                            row.payload(),
                            row.traceContext(),
                            row.publishAttempts() + 1,
                            row.version() + 1));
        }
        return claimed;
    }

    private static void requireOne(int changed, String operation) {
        if (changed != ONE_ROW) {
            throw new IllegalStateException(operation + " did not affect one row");
        }
    }

    private static OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record OutboxRow(
            UUID id,
            String eventType,
            String partitionKey,
            String payload,
            String traceContext,
            int publishAttempts,
            long version) {}
}
