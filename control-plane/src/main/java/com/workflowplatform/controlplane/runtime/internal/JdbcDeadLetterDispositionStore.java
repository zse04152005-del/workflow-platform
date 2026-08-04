package com.workflowplatform.controlplane.runtime.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.LengthPrefixedSha256;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionResult;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionStore;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionStore.DeadLetterAction;
import com.workflowplatform.controlplane.runtime.RuntimeControlResult;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL dead-letter actions coupled with idempotency, audit, Outbox and replay. */
@Component
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class JdbcDeadLetterDispositionStore implements DeadLetterDispositionStore {

    private static final int ACCEPTED = 202;
    private static final int ONE_ROW = 1;
    private static final int RESPONSE_REFERENCE_PARTS = 6;
    private static final String RESPONSE_REFERENCE_PREFIX = "dead-letter-action|";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RuntimeControlStore runtimeControls;
    private final ObjectMapper objectMapper;

    JdbcDeadLetterDispositionStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            RuntimeControlStore runtimeControlStore,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        runtimeControls = Objects.requireNonNull(runtimeControlStore, "runtimeControlStore");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public DeadLetterDispositionResult execute(DeadLetterDispositionCommand command) {
        Objects.requireNonNull(command, "command");
        DeadLetterDispositionResult result =
                transactions.execute(status -> executeInTransaction(command));
        if (result == null) {
            throw new IllegalStateException("dead-letter transaction returned no result");
        }
        return result;
    }

    private DeadLetterDispositionResult executeInTransaction(DeadLetterDispositionCommand command) {
        JdbcRuntimeSupport.setTenantContext(jdbc, command.context());
        IdempotencyClaim claim = claim(command);
        if (!claim.owner()) {
            return replay(claim.row());
        }
        DeadLetterRow row = lockDeadLetter(command);
        DeadLetterDispositionResult result =
                switch (command.action()) {
                    case NOTE -> addNote(command, row);
                    case REDISPATCH -> redispatch(command, row);
                };
        completeIdempotency(command, result);
        return result;
    }

    private DeadLetterDispositionResult addNote(
            DeadLetterDispositionCommand command, DeadLetterRow row) {
        Instant acceptedAt = JdbcRuntimeSupport.transactionTimestamp(jdbc);
        insertAudit(command, row, acceptedAt, row.status(), null);
        insertOutbox(command, row, acceptedAt, row.status(), null);
        return new DeadLetterDispositionResult(
                command.actionId(), acceptedAt, false, row.id(), row.status(), null);
    }

    private DeadLetterDispositionResult redispatch(
            DeadLetterDispositionCommand command, DeadLetterRow row) {
        if (!"OPEN".equals(row.status())) {
            throw ApiProblemException.of(ProblemCode.COMMON_CONFLICT_STATE);
        }
        String innerKeyDigest =
                LengthPrefixedSha256.digest(
                        "dead-letter-redispatch-inner-key-v1",
                        command.keyDigest(),
                        row.id().toString());
        RuntimeControlResult replay =
                runtimeControls.execute(
                        new RuntimeControlStore.RuntimeControlCommand(
                                command.context(),
                                row.sourceInstanceId(),
                                RuntimeControlAction.REPLAY,
                                command.noteRedacted(),
                                row.nodeId(),
                                command.traceId(),
                                TimeOrderedUuidV7.generate(),
                                "runtime:control:replay:" + row.sourceInstanceId(),
                                innerKeyDigest,
                                LengthPrefixedSha256.digest(
                                        "runtime-control-v1",
                                        row.sourceInstanceId().toString(),
                                        RuntimeControlAction.REPLAY.wireName(),
                                        command.noteRedacted(),
                                        row.nodeId())));
        Instant acceptedAt = JdbcRuntimeSupport.transactionTimestamp(jdbc);
        int changed =
                jdbc.update(
                        "UPDATE public.dead_letter SET status = 'REQUEUED', "
                                + "disposition_count = disposition_count + 1, "
                                + "last_disposition_at = ?, updated_at = ?, updated_by = ?, "
                                + "version = version + 1 WHERE tenant_id = ? AND id = ? "
                                + "AND status = 'OPEN' AND version = ?",
                        atUtc(acceptedAt),
                        atUtc(acceptedAt),
                        command.context().actorId(),
                        command.context().tenantId(),
                        row.id(),
                        row.version());
        requireOne(changed, "dead-letter redispatch disposition");
        insertAudit(command, row, acceptedAt, "REQUEUED", replay.targetInstanceId());
        insertOutbox(command, row, acceptedAt, "REQUEUED", replay.targetInstanceId());
        return new DeadLetterDispositionResult(
                command.actionId(),
                acceptedAt,
                false,
                row.id(),
                "REQUEUED",
                replay.targetInstanceId());
    }

    private DeadLetterRow lockDeadLetter(DeadLetterDispositionCommand command) {
        return jdbc
                .query(
                        "SELECT d.id, d.status, d.version, "
                                + "n.workflow_instance_id, n.node_id FROM public.dead_letter d "
                                + "JOIN public.node_instance n ON n.tenant_id = d.tenant_id "
                                + "AND n.id = d.node_instance_id WHERE d.tenant_id = ? "
                                + "AND d.id = ? FOR UPDATE OF d",
                        (result, rowNumber) ->
                                new DeadLetterRow(
                                        result.getObject("id", UUID.class),
                                        result.getString("status"),
                                        result.getLong("version"),
                                        result.getObject("workflow_instance_id", UUID.class),
                                        result.getString("node_id")),
                        command.context().tenantId(),
                        command.deadLetterId())
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiProblemException.of(ProblemCode.COMMON_NOT_FOUND_RESOURCE));
    }

    private void insertAudit(
            DeadLetterDispositionCommand command,
            DeadLetterRow row,
            Instant acceptedAt,
            String targetStatus,
            UUID targetInstanceId) {
        ObjectNode before = objectMapper.createObjectNode();
        before.put("status", row.status());
        ObjectNode after = objectMapper.createObjectNode();
        after.put("status", targetStatus);
        after.put(
                "noteDigest",
                LengthPrefixedSha256.digest("dead-letter-note-v1", command.noteRedacted()));
        if (targetInstanceId != null) {
            after.put("targetInstanceId", targetInstanceId.toString());
        }
        int inserted =
                jdbc.update(
                        "INSERT INTO public.audit_log (id, tenant_id, created_by, actor_type, "
                                + "actor_id, action, resource_type, resource_id, occurred_at, reason, "
                                + "trace_id, before_digest_or_redacted, after_digest_or_redacted) "
                                + "VALUES (?, ?, ?, 'USER', ?, ?, 'dead_letter', ?, ?, ?, ?, "
                                + "CAST(? AS jsonb), CAST(? AS jsonb))",
                        command.actionId(),
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        "runtime.dead_letter." + command.action().wireName(),
                        row.id().toString(),
                        atUtc(acceptedAt),
                        command.noteRedacted(),
                        command.traceId(),
                        json(before),
                        json(after));
        requireOne(inserted, "dead-letter audit insert");
    }

    private void insertOutbox(
            DeadLetterDispositionCommand command,
            DeadLetterRow row,
            Instant acceptedAt,
            String targetStatus,
            UUID targetInstanceId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", command.actionId().toString());
        envelope.put(
                "eventType",
                command.action() == DeadLetterAction.NOTE
                        ? "workflow.runtime.dead_letter.note.added"
                        : "workflow.runtime.dead_letter.redispatched");
        envelope.put("occurredAt", acceptedAt.toString());
        envelope.put("tenantId", command.context().tenantId().toString());
        envelope.put("traceId", command.traceId());
        envelope.put("schemaVersion", 1);
        envelope.put("producer", "control_plane");
        envelope.put("subject", "dead_letter/" + row.id());
        ObjectNode payload = envelope.putObject("payload");
        payload.put("entityType", "dead_letter");
        payload.put("entityId", row.id().toString());
        payload.put("status", targetStatus);
        payload.put(
                "noteDigest",
                LengthPrefixedSha256.digest("dead-letter-note-v1", command.noteRedacted()));
        if (targetInstanceId != null) {
            payload.put("targetInstanceId", targetInstanceId.toString());
        }
        String serialized = json(envelope);
        int inserted =
                jdbc.update(
                        "INSERT INTO public.outbox_event (id, tenant_id, created_by, "
                                + "aggregate_type, aggregate_id, event_type, schema_version, "
                                + "partition_key, payload, payload_digest, occurred_at, trace_context, "
                                + "next_publish_at) VALUES (?, ?, ?, 'dead_letter', ?, ?, 1, ?, "
                                + "CAST(? AS jsonb), ?, ?, ?, ?)",
                        command.actionId(),
                        command.context().tenantId(),
                        command.context().actorId(),
                        row.id(),
                        envelope.path("eventType").asText(),
                        command.context().tenantId() + ":" + row.id(),
                        serialized,
                        sha256(serialized),
                        atUtc(acceptedAt),
                        "00-" + command.traceId() + "-0000000000000001-01",
                        atUtc(acceptedAt));
        requireOne(inserted, "dead-letter Outbox insert");
    }

    // CPD-OFF: this idempotency boundary intentionally mirrors other transactional stores.
    private IdempotencyClaim claim(DeadLetterDispositionCommand command) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.idempotency_record (id, tenant_id, created_by, "
                                + "updated_by, operation_scope, key_digest, request_digest, status, "
                                + "expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', "
                                + "transaction_timestamp() + interval '24 hours') ON CONFLICT "
                                + "(tenant_id, operation_scope, key_digest) DO NOTHING",
                        command.idempotencyRecordId(),
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        command.operationScope(),
                        command.keyDigest(),
                        command.requestDigest());
        IdempotencyRow row = lockIdempotency(command);
        if (inserted == ONE_ROW) {
            return new IdempotencyClaim(true, row);
        }
        if (row.expired()) {
            resetExpiredIdempotency(command);
            return new IdempotencyClaim(true, row);
        }
        if (!command.requestDigest().equals(row.requestDigest())
                || !"COMPLETED".equals(row.status())) {
            throw ApiProblemException.of(ProblemCode.COMMON_CONFLICT_STATE);
        }
        return new IdempotencyClaim(false, row);
    }

    // CPD-ON

    private IdempotencyRow lockIdempotency(DeadLetterDispositionCommand command) {
        return jdbc
                .query(
                        "SELECT request_digest, status, response_ref, "
                                + "expires_at <= transaction_timestamp() AS expired FROM "
                                + "public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ? AND key_digest = ? FOR UPDATE",
                        (result, rowNumber) ->
                                new IdempotencyRow(
                                        result.getString("request_digest"),
                                        result.getString("status"),
                                        result.getString("response_ref"),
                                        result.getBoolean("expired")),
                        command.context().tenantId(),
                        command.operationScope(),
                        command.keyDigest())
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("dead-letter idempotency row is missing"));
    }

    private void resetExpiredIdempotency(DeadLetterDispositionCommand command) {
        int changed =
                jdbc.update(
                        "UPDATE public.idempotency_record SET request_digest = ?, "
                                + "status = 'PROCESSING', response_status = NULL, response_ref = NULL, "
                                + "response_digest = NULL, expires_at = transaction_timestamp() "
                                + "+ interval '24 hours', updated_at = transaction_timestamp(), "
                                + "updated_by = ?, version = version + 1 WHERE tenant_id = ? "
                                + "AND operation_scope = ? AND key_digest = ?",
                        command.requestDigest(),
                        command.context().actorId(),
                        command.context().tenantId(),
                        command.operationScope(),
                        command.keyDigest());
        requireOne(changed, "expired dead-letter idempotency reset");
    }

    private void completeIdempotency(
            DeadLetterDispositionCommand command, DeadLetterDispositionResult result) {
        String responseReference = reference(result);
        int changed =
                jdbc.update(
                        "UPDATE public.idempotency_record SET status = 'COMPLETED', "
                                + "response_status = ?, response_ref = ?, response_digest = ?, "
                                + "updated_at = transaction_timestamp(), updated_by = ?, "
                                + "version = version + 1 WHERE tenant_id = ? AND operation_scope = ? "
                                + "AND key_digest = ? AND status = 'PROCESSING'",
                        ACCEPTED,
                        responseReference,
                        LengthPrefixedSha256.digest("dead-letter-response-v1", responseReference),
                        command.context().actorId(),
                        command.context().tenantId(),
                        command.operationScope(),
                        command.keyDigest());
        requireOne(changed, "dead-letter idempotency completion");
    }

    private static String reference(DeadLetterDispositionResult result) {
        return RESPONSE_REFERENCE_PREFIX
                + result.actionId()
                + "|"
                + result.acceptedAt()
                + "|"
                + result.deadLetterId()
                + "|"
                + result.deadLetterStatus()
                + "|"
                + (result.targetInstanceId() == null ? "-" : result.targetInstanceId());
    }

    private static DeadLetterDispositionResult replay(IdempotencyRow row) {
        String reference = row.responseReference();
        if (reference == null || !reference.startsWith(RESPONSE_REFERENCE_PREFIX)) {
            throw new IllegalStateException("completed dead-letter response reference is invalid");
        }
        String[] parts = reference.split("\\|", -1);
        if (parts.length != RESPONSE_REFERENCE_PARTS) {
            throw new IllegalStateException(
                    "completed dead-letter response reference is malformed");
        }
        return new DeadLetterDispositionResult(
                UUID.fromString(parts[1]),
                Instant.parse(parts[2]),
                true,
                UUID.fromString(parts[3]),
                parts[4],
                "-".equals(parts[5]) ? null : UUID.fromString(parts[5]));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("dead-letter event cannot be serialized", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireOne(int changed, String operation) {
        if (changed != ONE_ROW) {
            throw new IllegalStateException(operation + " did not affect one row");
        }
    }

    private static OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record IdempotencyClaim(boolean owner, IdempotencyRow row) {}

    private record IdempotencyRow(
            String requestDigest, String status, String responseReference, boolean expired) {}

    private record DeadLetterRow(
            UUID id, String status, long version, UUID sourceInstanceId, String nodeId) {}
}
