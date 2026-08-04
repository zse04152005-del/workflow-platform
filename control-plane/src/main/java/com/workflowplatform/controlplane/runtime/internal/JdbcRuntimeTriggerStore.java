package com.workflowplatform.controlplane.runtime.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerResult;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerStore;
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

/** PostgreSQL trigger store: idempotency claim, snapshot creation and Outbox append are atomic. */
@Component
final class JdbcRuntimeTriggerStore implements RuntimeTriggerStore {

    private static final int ONE_ROW = 1;
    private static final int ACCEPTED = 202;
    private static final String COMPLETED = "COMPLETED";
    private static final String ACTIVE = "ACTIVE";
    private static final String CREATED = "CREATED";
    private static final String HTTP_NODE_TYPE = "http";
    private static final String STATUS_COLUMN = "status";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    JdbcRuntimeTriggerStore(
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
    public RuntimeTriggerResult trigger(RuntimeTriggerCommand command) {
        RuntimeTriggerResult result =
                transactions.execute(
                        status -> {
                            JdbcRuntimeSupport.setTenantContext(jdbc, command.context());
                            Claim claim = claim(command);
                            if (!claim.owner()) {
                                return replay(command, claim);
                            }
                            PublishedVersion version = publishedVersion(command);
                            UUID instanceId = TimeOrderedUuidV7.generate();
                            Instant acceptedAt = JdbcRuntimeSupport.transactionTimestamp(jdbc);
                            String inputDigest = digest(command.input().toString());
                            insertInstance(command, version, instanceId, inputDigest);
                            insertNodes(command, version, instanceId);
                            UUID eventId = TimeOrderedUuidV7.generate();
                            insertAudit(command, version, instanceId, eventId, acceptedAt);
                            insertOutbox(command, version, instanceId, eventId, acceptedAt);
                            complete(command, instanceId, inputDigest);
                            return new RuntimeTriggerResult(
                                    instanceId,
                                    version.id(),
                                    version.versionNo(),
                                    CREATED,
                                    false,
                                    ACCEPTED,
                                    command.requestDigest(),
                                    acceptedAt);
                        });
        if (result == null) {
            throw new IllegalStateException("trigger transaction returned no result");
        }
        return result;
    }

    private Claim claim(RuntimeTriggerCommand command) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.idempotency_record "
                                + "(id, tenant_id, created_by, updated_by, operation_scope, key_digest, "
                                + "request_digest, status, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', "
                                + "transaction_timestamp() + interval '24 hours') "
                                + "ON CONFLICT (tenant_id, operation_scope, key_digest) DO NOTHING",
                        command.idempotencyRecordId(),
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        command.idempotencyScope(),
                        command.keyDigest(),
                        command.requestDigest());
        List<IdempotencyRow> rows =
                jdbc.query(
                        "SELECT request_digest, status, response_status, response_ref, response_digest, "
                                + "expires_at <= transaction_timestamp() AS expired FROM public.idempotency_record "
                                + "WHERE tenant_id = ? AND operation_scope = ? AND key_digest = ? FOR UPDATE",
                        (result, rowNumber) ->
                                new IdempotencyRow(
                                        result.getString("request_digest"),
                                        result.getString(STATUS_COLUMN),
                                        (Integer) result.getObject("response_status"),
                                        result.getString("response_ref"),
                                        result.getString("response_digest"),
                                        result.getBoolean("expired")),
                        command.context().tenantId(),
                        command.idempotencyScope(),
                        command.keyDigest());
        if (rows.size() != ONE_ROW) {
            throw new IllegalStateException("trigger idempotency claim did not resolve one row");
        }
        IdempotencyRow row = rows.getFirst();
        if (inserted == ONE_ROW) {
            return new Claim(true, row);
        }
        if (row.expired()) {
            jdbc.update(
                    "UPDATE public.idempotency_record SET request_digest = ?, status = 'PROCESSING', "
                            + "response_status = NULL, response_ref = NULL, response_digest = NULL, "
                            + "expires_at = transaction_timestamp() + interval '24 hours', "
                            + "updated_at = transaction_timestamp(), updated_by = ?, version = version + 1 "
                            + "WHERE tenant_id = ? AND operation_scope = ? AND key_digest = ?",
                    command.requestDigest(),
                    command.context().actorId(),
                    command.context().tenantId(),
                    command.idempotencyScope(),
                    command.keyDigest());
            return new Claim(true, row);
        }
        if (!command.requestDigest().equals(row.requestDigest())
                || !COMPLETED.equals(row.status())) {
            throw new IllegalArgumentException(
                    "Idempotency-Key was already used with a different request");
        }
        return new Claim(false, row);
    }

    private RuntimeTriggerResult replay(RuntimeTriggerCommand command, Claim claim) {
        UUID instanceId = instanceId(claim.row().responseReference());
        List<InstanceRow> rows =
                jdbc.query(
                        "SELECT i.id, i.workflow_version_id, v.version_no, i.status, i.created_at "
                                + "FROM public.workflow_instance i JOIN public.workflow_version v "
                                + "ON v.tenant_id = i.tenant_id AND v.id = i.workflow_version_id "
                                + "WHERE i.tenant_id = ? AND i.id = ?",
                        (result, rowNumber) ->
                                new InstanceRow(
                                        result.getObject("id", UUID.class),
                                        result.getObject("workflow_version_id", UUID.class),
                                        result.getInt("version_no"),
                                        result.getString(STATUS_COLUMN),
                                        result.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()),
                        command.context().tenantId(),
                        instanceId);
        if (rows.size() != ONE_ROW) {
            throw new IllegalStateException("idempotency replay instance is missing");
        }
        InstanceRow row = rows.getFirst();
        return new RuntimeTriggerResult(
                row.instanceId(),
                row.workflowVersionId(),
                row.versionNo(),
                row.status(),
                true,
                claim.row().responseStatus() == null ? ACCEPTED : claim.row().responseStatus(),
                command.requestDigest(),
                row.createdAt());
    }

    private PublishedVersion publishedVersion(RuntimeTriggerCommand command) {
        List<PublishedVersion> rows =
                jdbc.query(
                        "SELECT v.id, v.definition_id, d.project_id, v.version_no, v.dsl "
                                + "FROM public.workflow_version v "
                                + "JOIN public.workflow_definition d ON d.tenant_id = v.tenant_id "
                                + "AND d.id = v.definition_id WHERE v.tenant_id = ? AND d.id = ? "
                                + "AND d.status = ? ORDER BY v.version_no DESC LIMIT 1",
                        (result, rowNumber) ->
                                new PublishedVersion(
                                        result.getObject("id", UUID.class),
                                        result.getObject("definition_id", UUID.class),
                                        result.getObject("project_id", UUID.class),
                                        result.getInt("version_no"),
                                        parseDsl(result.getString("dsl"))),
                        command.context().tenantId(),
                        command.workflowId(),
                        ACTIVE);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("workflow has no active published version");
        }
        return rows.getFirst();
    }

    private void insertInstance(
            RuntimeTriggerCommand command,
            PublishedVersion version,
            UUID instanceId,
            String inputDigest) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                                + "definition_id, workflow_version_id, business_key, status, input_ref, "
                                + "input_digest, input_payload, trace_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATED', NULL, ?, CAST(? AS jsonb), ?)",
                        instanceId,
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        version.definitionId(),
                        version.id(),
                        command.businessKey(),
                        inputDigest,
                        json(command.input()),
                        command.traceId());
        requireOne(inserted, "workflow instance insert");
    }

    private void insertNodes(
            RuntimeTriggerCommand command, PublishedVersion version, UUID instanceId) {
        JsonNode nodes = version.dsl().path("nodes");
        if (!nodes.isArray()) {
            throw new IllegalArgumentException("published workflow DSL has no nodes");
        }
        for (JsonNode node : nodes) {
            String nodeId = node.path("id").asText("");
            String nodeType = node.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
            if (nodeId.isBlank() || nodeType.isBlank()) {
                throw new IllegalArgumentException("published workflow node identity is invalid");
            }
            jdbc.update(
                    "INSERT INTO public.node_instance (id, tenant_id, created_by, updated_by, "
                            + "workflow_instance_id, node_id, node_type, status, config_snapshot) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', CAST(? AS jsonb))",
                    TimeOrderedUuidV7.generate(),
                    command.context().tenantId(),
                    command.context().actorId(),
                    command.context().actorId(),
                    instanceId,
                    nodeId,
                    nodeType,
                    json(executionConfigSnapshot(command, version, node)));
        }
    }

    private ObjectNode executionConfigSnapshot(
            RuntimeTriggerCommand command, PublishedVersion version, JsonNode node) {
        ObjectNode snapshot =
                node.path("config").isObject()
                        ? ((ObjectNode) node.path("config")).deepCopy()
                        : objectMapper.createObjectNode();
        snapshot.put("_executorVersion", node.path("executorVersion").asInt(1));
        snapshot.put("_timeout", node.path("timeout").asText("PT30S"));
        if (node.path("retry").isObject()) {
            snapshot.set("_retry", node.path("retry").deepCopy());
        }
        if (HTTP_NODE_TYPE.equalsIgnoreCase(node.path("type").asText())) {
            snapshot.put(
                    "_managedEndpoint",
                    managedEndpoint(
                            command, version.projectId(), snapshot.path("connectorRef").asText()));
        }
        return snapshot;
    }

    private String managedEndpoint(
            RuntimeTriggerCommand command, UUID projectId, String connectorReference) {
        int separator = connectorReference.indexOf('.');
        if (separator < 1 || separator == connectorReference.length() - 1) {
            throw new IllegalArgumentException("HTTP node connector reference is invalid");
        }
        String connectorType = connectorReference.substring(0, separator);
        String connectorName = connectorReference.substring(separator + 1);
        List<String> endpoints =
                jdbc.query(
                        "SELECT endpoint FROM public.connector WHERE tenant_id = ? "
                                + "AND (project_id = ? OR project_id IS NULL) AND connector_type = ? "
                                + "AND normalized_name = ? AND status = 'ACTIVE' AND deleted_at IS NULL "
                                + "ORDER BY project_id NULLS LAST LIMIT 1",
                        (result, rowNumber) -> result.getString("endpoint"),
                        command.context().tenantId(),
                        projectId,
                        connectorType,
                        connectorName);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("HTTP node managed connector is unavailable");
        }
        return endpoints.getFirst();
    }

    private void insertAudit(
            RuntimeTriggerCommand command,
            PublishedVersion version,
            UUID instanceId,
            UUID eventId,
            Instant acceptedAt) {
        ObjectNode before = objectMapper.createObjectNode();
        before.put(STATUS_COLUMN, "<none>");
        ObjectNode after = objectMapper.createObjectNode();
        after.put(STATUS_COLUMN, CREATED);
        after.put("instanceId", instanceId.toString());
        after.put("workflowVersionId", version.id().toString());
        jdbc.update(
                "INSERT INTO public.audit_log (id, tenant_id, created_by, actor_type, actor_id, action, "
                        + "resource_type, resource_id, occurred_at, reason, trace_id, "
                        + "before_digest_or_redacted, after_digest_or_redacted) "
                        + "VALUES (?, ?, ?, 'SERVICE', ?, ?, 'workflow_instance', ?, ?, ?, ?, "
                        + "CAST(? AS jsonb), CAST(? AS jsonb))",
                eventId,
                command.context().tenantId(),
                command.context().actorId(),
                command.context().actorId(),
                "runtime.instance.trigger." + command.triggerType(),
                instanceId.toString(),
                atUtc(acceptedAt),
                "TRIGGER_ACCEPTED",
                command.traceId(),
                json(before),
                json(after));
    }

    private void insertOutbox(
            RuntimeTriggerCommand command,
            PublishedVersion version,
            UUID instanceId,
            UUID eventId,
            Instant acceptedAt) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "workflow.instance.created");
        envelope.put("occurredAt", acceptedAt.toString());
        envelope.put("tenantId", command.context().tenantId().toString());
        envelope.put("traceId", command.traceId());
        envelope.put("schemaVersion", 1);
        envelope.put("producer", "control_plane");
        envelope.put("subject", "workflow_instance/" + instanceId);
        ObjectNode payload = envelope.putObject("payload");
        payload.put("instanceId", instanceId.toString());
        payload.put("workflowVersionId", version.id().toString());
        payload.put("workflowVersion", version.versionNo());
        payload.put("triggerType", command.triggerType());
        payload.put("businessKey", command.businessKey());
        String serialized = json(envelope);
        jdbc.update(
                "INSERT INTO public.outbox_event (id, tenant_id, created_by, aggregate_type, aggregate_id, event_type, "
                        + "schema_version, partition_key, payload, payload_digest, occurred_at, "
                        + "trace_context, next_publish_at) "
                        + "VALUES (?, ?, ?, 'workflow_instance', ?, 'workflow.instance.created', 1, ?, "
                        + "CAST(? AS jsonb), ?, ?, ?, ?)",
                eventId,
                command.context().tenantId(),
                command.context().actorId(),
                instanceId,
                command.context().tenantId() + ":" + instanceId,
                serialized,
                digest(serialized),
                atUtc(acceptedAt),
                "00-" + command.traceId() + "-0000000000000001-01",
                atUtc(acceptedAt));
    }

    private void complete(RuntimeTriggerCommand command, UUID instanceId, String inputDigest) {
        int changed =
                jdbc.update(
                        "UPDATE public.idempotency_record SET status = 'COMPLETED', response_status = ?, "
                                + "response_ref = ?, response_digest = ?, updated_at = transaction_timestamp(), "
                                + "version = version + 1 WHERE tenant_id = ? AND operation_scope = ? "
                                + "AND key_digest = ? AND status = 'PROCESSING'",
                        ACCEPTED,
                        "/api/v1/instances/" + instanceId,
                        digest(instanceId.toString(), inputDigest),
                        command.context().tenantId(),
                        command.idempotencyScope(),
                        command.keyDigest());
        requireOne(changed, "trigger idempotency completion");
    }

    private UUID instanceId(String reference) {
        if (reference == null || !reference.startsWith("/api/v1/instances/")) {
            throw new IllegalStateException("invalid trigger replay reference");
        }
        return UUID.fromString(reference.substring("/api/v1/instances/".length()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("trigger event cannot be serialized", exception);
        }
    }

    private JsonNode parseDsl(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("published workflow DSL cannot be read", exception);
        }
    }

    private static OffsetDateTime atUtc(Instant timestamp) {
        return OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC);
    }

    private static void requireOne(int count, String operation) {
        if (count != ONE_ROW) {
            throw new IllegalStateException(operation + " did not affect one row");
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Claim(boolean owner, IdempotencyRow row) {}

    private record IdempotencyRow(
            String requestDigest,
            String status,
            Integer responseStatus,
            String responseReference,
            String responseDigest,
            boolean expired) {}

    private record PublishedVersion(
            UUID id, UUID definitionId, UUID projectId, int versionNo, JsonNode dsl) {}

    private record InstanceRow(
            UUID instanceId,
            UUID workflowVersionId,
            int versionNo,
            String status,
            Instant createdAt) {}
}
