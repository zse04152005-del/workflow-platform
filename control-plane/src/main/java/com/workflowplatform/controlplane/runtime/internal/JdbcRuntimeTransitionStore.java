package com.workflowplatform.controlplane.runtime.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionCommand;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionEffects;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionException;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionResult;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation that commits state, audit and Outbox as one transaction. */
@Component
@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
final class JdbcRuntimeTransitionStore implements RuntimeTransitionStore {

    private static final String EXPECTED_ROW = "expected one runtime state row";
    private static final String WORKER_ACTOR = "worker";
    private static final int ONE_ROW = 1;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    JdbcRuntimeTransitionStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public RuntimeTransitionResult transition(RuntimeTransitionCommand command, String eventType) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(eventType, "eventType");
        RuntimeTransitionResult result =
                transactions.execute(
                        status -> {
                            JdbcRuntimeSupport.setTenantContext(jdbc, command.context());
                            Instant occurredAt = JdbcRuntimeSupport.transactionTimestamp(jdbc);
                            validateTemporalEffects(command.effects(), occurredAt);
                            long nextVersion = updateState(command, occurredAt);
                            UUID eventId = TimeOrderedUuidV7.generate();
                            insertAudit(command, eventId, occurredAt, nextVersion);
                            insertOutbox(command, eventId, eventType, occurredAt, nextVersion);
                            return new RuntimeTransitionResult(
                                    command.entityType(),
                                    command.entityId(),
                                    command.command(),
                                    command.expectedStatus(),
                                    command.targetStatus(),
                                    nextVersion,
                                    eventId,
                                    occurredAt);
                        });
        if (result == null) {
            throw new IllegalStateException("runtime transition transaction returned no result");
        }
        return result;
    }

    private long updateState(RuntimeTransitionCommand command, Instant occurredAt) {
        List<Long> versions =
                switch (command.entityType()) {
                    case INSTANCE -> updateInstance(command, occurredAt);
                    case NODE -> updateNode(command, occurredAt);
                    case ATTEMPT -> updateAttempt(command, occurredAt);
                    case SCHEDULE -> updateSchedule(command, occurredAt);
                };
        if (versions.size() == ONE_ROW) {
            return versions.getFirst();
        }
        classifyZeroRow(command);
        throw new IllegalStateException(EXPECTED_ROW);
    }

    private List<Long> updateInstance(RuntimeTransitionCommand command, Instant occurredAt) {
        boolean starts = "start".equals(command.command());
        boolean completes = SetOf.INSTANCE.contains(command.targetStatus());
        return jdbc.query(
                "UPDATE public.workflow_instance "
                        + "SET status = ?, updated_at = ?, updated_by = ?, version = version + 1, "
                        + "started_at = CASE WHEN ? THEN ? ELSE started_at END, "
                        + "completed_at = CASE WHEN ? THEN ? ELSE completed_at END "
                        + "WHERE tenant_id = ? AND id = ? AND status = ? AND version = ? "
                        + "RETURNING version",
                (result, rowNumber) -> result.getLong("version"),
                command.targetStatus(),
                atUtc(occurredAt),
                command.context().actorId(),
                starts,
                atUtc(occurredAt),
                completes,
                atUtc(occurredAt),
                command.context().tenantId(),
                command.entityId(),
                command.expectedStatus(),
                command.expectedVersion());
    }

    private List<Long> updateNode(RuntimeTransitionCommand command, Instant occurredAt) {
        RuntimeTransitionEffects effects = command.effects();
        boolean workerGuard = WORKER_ACTOR.equals(command.actor().wireName());
        String guard =
                workerGuard
                        ? " AND current_worker_id = ? AND lease_generation = ? AND lease_expire_at > ?"
                        : "";
        String sql =
                "UPDATE public.node_instance "
                        + "SET status = ?, updated_at = ?, updated_by = ?, version = version + 1, "
                        + "next_schedule_at = ?, "
                        + "current_worker_id = CASE WHEN ? = 'DISPATCHED' THEN ? "
                        + "WHEN ? = 'RUNNING' THEN current_worker_id ELSE NULL END, "
                        + "lease_expire_at = CASE WHEN ? = 'DISPATCHED' THEN ? "
                        + "WHEN ? = 'RUNNING' THEN lease_expire_at ELSE NULL END, "
                        + "lease_generation = CASE WHEN ? = 'DISPATCHED' "
                        + "THEN lease_generation + 1 ELSE lease_generation END, "
                        + "dispatch_count = CASE WHEN ? = 'DISPATCHED' "
                        + "THEN dispatch_count + 1 ELSE dispatch_count END, "
                        + "attempt_no = CASE WHEN ? = 'RUNNING' THEN attempt_no + 1 ELSE attempt_no END, "
                        + "output_ref = CASE WHEN ? = 'SUCCEEDED' THEN ? ELSE NULL END, "
                        + "output_digest = CASE WHEN ? = 'SUCCEEDED' THEN ? ELSE NULL END, "
                        + "error_code = CASE WHEN ? IN ('FAILED', 'RETRY_WAIT') THEN ? ELSE NULL END "
                        + "WHERE tenant_id = ? AND id = ? AND status = ? AND version = ?"
                        + guard
                        + " RETURNING version";
        List<Object> arguments =
                new java.util.ArrayList<>(
                        java.util.Arrays.asList(
                                command.targetStatus(),
                                atUtc(occurredAt),
                                command.context().actorId(),
                                effects.nextScheduleAt() == null
                                        ? null
                                        : atUtc(effects.nextScheduleAt()),
                                command.targetStatus(),
                                effects.workerId(),
                                command.targetStatus(),
                                command.targetStatus(),
                                effects.leaseExpiresAt() == null
                                        ? null
                                        : atUtc(effects.leaseExpiresAt()),
                                command.targetStatus(),
                                command.targetStatus(),
                                command.targetStatus(),
                                command.targetStatus(),
                                command.targetStatus(),
                                effects.outputReference(),
                                command.targetStatus(),
                                effects.outputDigest(),
                                command.targetStatus(),
                                effects.errorCode(),
                                command.context().tenantId(),
                                command.entityId(),
                                command.expectedStatus(),
                                command.expectedVersion()));
        if (workerGuard) {
            arguments.add(effects.requireWorkerId());
            arguments.add(effects.requireExpectedLeaseGeneration());
            arguments.add(atUtc(occurredAt));
        }
        return jdbc.query(
                sql, (result, rowNumber) -> result.getLong("version"), arguments.toArray());
    }

    private List<Long> updateAttempt(RuntimeTransitionCommand command, Instant occurredAt) {
        RuntimeTransitionEffects effects = command.effects();
        boolean workerGuard = WORKER_ACTOR.equals(command.actor().wireName());
        String guard =
                workerGuard
                        ? " AND EXISTS (SELECT 1 FROM public.node_instance n "
                                + "WHERE n.tenant_id = task_execution.tenant_id "
                                + "AND n.id = task_execution.node_instance_id "
                                + "AND n.current_worker_id = ? AND n.lease_generation = ? "
                                + "AND n.lease_expire_at > ?)"
                        : "";
        String sql =
                "UPDATE public.task_execution "
                        + "SET status = ?, updated_at = ?, updated_by = ?, version = version + 1, "
                        + "finished_at = ?, "
                        + "output_ref = CASE WHEN ? = 'SUCCEEDED' THEN ? ELSE NULL END, "
                        + "output_digest = CASE WHEN ? = 'SUCCEEDED' THEN ? ELSE NULL END, "
                        + "error_code = CASE WHEN ? <> 'SUCCEEDED' THEN ? ELSE NULL END, "
                        + "error_summary_redacted = CASE WHEN ? <> 'SUCCEEDED' THEN ? ELSE NULL END "
                        + "WHERE tenant_id = ? AND id = ? AND status = ? AND version = ?"
                        + guard
                        + " RETURNING version";
        List<Object> arguments =
                new java.util.ArrayList<>(
                        java.util.Arrays.asList(
                                command.targetStatus(),
                                atUtc(occurredAt),
                                command.context().actorId(),
                                atUtc(occurredAt),
                                command.targetStatus(),
                                effects.outputReference(),
                                command.targetStatus(),
                                effects.outputDigest(),
                                command.targetStatus(),
                                effects.errorCode(),
                                command.targetStatus(),
                                effects.errorSummaryRedacted(),
                                command.context().tenantId(),
                                command.entityId(),
                                command.expectedStatus(),
                                command.expectedVersion()));
        if (workerGuard) {
            arguments.add(effects.requireWorkerId());
            arguments.add(effects.requireExpectedLeaseGeneration());
            arguments.add(atUtc(occurredAt));
        }
        return jdbc.query(
                sql, (result, rowNumber) -> result.getLong("version"), arguments.toArray());
    }

    private List<Long> updateSchedule(RuntimeTransitionCommand command, Instant occurredAt) {
        RuntimeTransitionEffects effects = command.effects();
        return jdbc.query(
                "UPDATE public.schedule_occurrence "
                        + "SET status = ?, updated_at = ?, updated_by = ?, version = version + 1, "
                        + "claimed_at = CASE WHEN ? IN ('CLAIMED', 'SKIPPED') THEN ? ELSE claimed_at END, "
                        + "workflow_instance_id = CASE WHEN ? = 'FIRED' "
                        + "THEN CAST(? AS uuid) ELSE NULL::uuid END "
                        + "WHERE tenant_id = ? AND id = ? AND status = ? AND version = ? "
                        + "RETURNING version",
                (result, rowNumber) -> result.getLong("version"),
                command.targetStatus(),
                atUtc(occurredAt),
                command.context().actorId(),
                command.targetStatus(),
                atUtc(occurredAt),
                command.targetStatus(),
                effects.workflowInstanceId(),
                command.context().tenantId(),
                command.entityId(),
                command.expectedStatus(),
                command.expectedVersion());
    }

    private void classifyZeroRow(RuntimeTransitionCommand command) {
        RowSnapshot current = currentRow(command);
        if (current == null) {
            throw failure(RuntimeTransitionException.Failure.NOT_FOUND);
        }
        if (current.version() != command.expectedVersion()) {
            throw failure(RuntimeTransitionException.Failure.VERSION_CONFLICT);
        }
        if (!current.status().equals(command.expectedStatus())) {
            throw failure(RuntimeTransitionException.Failure.INVALID_STATE_TRANSITION);
        }
        if (WORKER_ACTOR.equals(command.actor().wireName())) {
            throw failure(RuntimeTransitionException.Failure.FENCING_TOKEN_CONFLICT);
        }
        throw failure(RuntimeTransitionException.Failure.VERSION_CONFLICT);
    }

    private RowSnapshot currentRow(RuntimeTransitionCommand command) {
        return jdbc
                .query(
                        "SELECT status, version FROM public."
                                + command.entityType().tableName()
                                + " WHERE tenant_id = ? AND id = ?",
                        (result, rowNumber) ->
                                new RowSnapshot(
                                        result.getString("status"), result.getLong("version")),
                        command.context().tenantId(),
                        command.entityId())
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void insertAudit(
            RuntimeTransitionCommand command, UUID eventId, Instant occurredAt, long nextVersion) {
        ObjectNode before = objectMapper.createObjectNode();
        before.put("status", command.expectedStatus());
        before.put("version", command.expectedVersion());
        ObjectNode after = objectMapper.createObjectNode();
        after.put("status", command.targetStatus());
        after.put("version", nextVersion);
        after.put("command", command.command());
        after.put("eventId", eventId.toString());
        if (command.effects().errorSummaryRedacted() != null) {
            after.put("errorSummaryDigest", sha256(command.effects().errorSummaryRedacted()));
        }
        int inserted =
                jdbc.update(
                        "INSERT INTO public.audit_log "
                                + "(id, tenant_id, created_by, actor_type, actor_id, action, "
                                + "resource_type, resource_id, occurred_at, reason, trace_id, "
                                + "before_digest_or_redacted, after_digest_or_redacted) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                        eventId,
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.actor().auditType(),
                        command.context().actorId(),
                        "runtime." + command.entityType().wireName() + "." + command.command(),
                        command.entityType().tableName(),
                        command.entityId().toString(),
                        atUtc(occurredAt),
                        command.reasonCode(),
                        command.traceId(),
                        json(before),
                        json(after));
        requireSingleRow(inserted, "runtime audit insert");
    }

    private void insertOutbox(
            RuntimeTransitionCommand command,
            UUID eventId,
            String eventType,
            Instant occurredAt,
            long nextVersion) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("tenantId", command.context().tenantId().toString());
        envelope.put("traceId", command.traceId());
        envelope.put("schemaVersion", 1);
        envelope.put("producer", "control_plane");
        envelope.put("subject", command.entityType().wireName() + "/" + command.entityId());
        envelope.put("dataContentType", "application/json");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("entityType", command.entityType().wireName());
        payload.put("entityId", command.entityId().toString());
        payload.put("command", command.command());
        payload.put("fromStatus", command.expectedStatus());
        payload.put("toStatus", command.targetStatus());
        payload.put("version", nextVersion);
        payload.put("reasonCode", command.reasonCode());
        envelope.set("payload", payload);
        String serialized = json(envelope);
        int inserted =
                jdbc.update(
                        "INSERT INTO public.outbox_event "
                                + "(id, tenant_id, created_by, aggregate_type, aggregate_id, "
                                + "event_type, schema_version, partition_key, payload, payload_digest, "
                                + "occurred_at, trace_context, next_publish_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, CAST(? AS jsonb), ?, ?, ?, ?)",
                        eventId,
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.entityType().tableName(),
                        command.entityId(),
                        eventType,
                        command.context().tenantId() + ":" + command.entityId(),
                        serialized,
                        sha256(serialized),
                        atUtc(occurredAt),
                        "00-" + command.traceId() + "-0000000000000001-01",
                        atUtc(occurredAt));
        requireSingleRow(inserted, "runtime Outbox insert");
    }

    private static void validateTemporalEffects(RuntimeTransitionEffects effects, Instant now) {
        if (effects.nextScheduleAt() != null && effects.nextScheduleAt().isBefore(now)) {
            throw failure(RuntimeTransitionException.Failure.INVALID_EFFECTS);
        }
        if (effects.leaseExpiresAt() != null && !effects.leaseExpiresAt().isAfter(now)) {
            throw failure(RuntimeTransitionException.Failure.INVALID_EFFECTS);
        }
    }

    private static OffsetDateTime atUtc(Instant timestamp) {
        return OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC);
    }

    private static void requireSingleRow(int count, String operation) {
        if (count != ONE_ROW) {
            throw new IllegalStateException(operation + " did not affect one row");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "runtime transition payload could not be serialized", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required SHA-256 algorithm is unavailable", exception);
        }
    }

    private static RuntimeTransitionException failure(RuntimeTransitionException.Failure failure) {
        return new RuntimeTransitionException(failure);
    }

    private record RowSnapshot(String status, long version) {}

    private static final class SetOf {
        private static final java.util.Set<String> INSTANCE =
                java.util.Set.of("CANCELLED", "SUCCEEDED", "FAILED");

        private SetOf() {}
    }
}
