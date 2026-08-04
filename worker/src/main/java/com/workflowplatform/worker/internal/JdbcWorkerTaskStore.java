package com.workflowplatform.worker.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.security.SensitiveTextRedactor;
import com.workflowplatform.worker.WorkerTaskIds;
import com.workflowplatform.worker.WorkerTaskStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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

/** PostgreSQL Worker store enforcing the current lease generation on every result write. */
@Component
final class JdbcWorkerTaskStore implements WorkerTaskStore {

    private static final int ONE_ROW = 1;
    private static final String FAILED = "FAILED";
    private static final String RUNNING = "RUNNING";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final WorkerRetryPlanner retryPlanner;

    JdbcWorkerTaskStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        retryPlanner = new WorkerRetryPlanner(objectMapper);
    }

    @Override
    public ClaimResult claim(TaskReadyEvent event, String consumerName, String workerInstanceId) {
        ClaimResult result =
                transactions.execute(
                        status -> claimInTransaction(event, consumerName, workerInstanceId));
        if (result == null) {
            throw new IllegalStateException("Worker claim transaction returned no result");
        }
        return result;
    }

    @Override
    public void heartbeat(ClaimedTask task, String workerInstanceId, Duration extension) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(extension, "extension");
        if (extension.isNegative()
                || extension.isZero()
                || extension.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("heartbeat extension must be positive and bounded");
        }
        transactions.executeWithoutResult(
                status -> heartbeatInTransaction(task, workerInstanceId, extension));
    }

    @Override
    public void complete(ClaimedTask task, String workerInstanceId, NodeExecutionResult result) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(result, "result");
        transactions.executeWithoutResult(
                status -> completeInTransaction(task, workerInstanceId, result));
    }

    private ClaimResult claimInTransaction(
            TaskReadyEvent event, String consumerName, String workerInstanceId) {
        setTenant(event.tenantId());
        if (processed(consumerName, event.eventId())) {
            return ClaimResult.duplicate();
        }
        NodeRow node = lockNode(event.tenantId(), event.nodeInstanceId());
        if (node == null) {
            return ClaimResult.stale();
        }
        if (!"DISPATCHED".equals(node.status())) {
            if ("READY".equals(node.status())
                    || "RETRY_WAIT".equals(node.status())
                    || "WAITING_TIMER".equals(node.status())) {
                throw new TaskNotReadyException("task has not been dispatched yet");
            }
            return ClaimResult.stale();
        }
        Instant now = transactionTimestamp();
        if (node.dispatchToken() == null || !node.leaseExpiresAt().isAfter(now)) {
            throw new TaskNotReadyException("task dispatch lease is missing or expired");
        }
        int nextAttempt = node.attemptNo() + 1;
        int changed =
                jdbc.update(
                        "UPDATE public.node_instance SET status = 'RUNNING', "
                                + "attempt_no = attempt_no + 1, updated_at = ?, updated_by = ?, "
                                + "version = version + 1 WHERE tenant_id = ? AND id = ? "
                                + "AND status = 'DISPATCHED' AND version = ? "
                                + "AND current_worker_id = ? AND lease_generation = ? "
                                + "AND lease_expire_at > ?",
                        atUtc(now),
                        workerInstanceId,
                        event.tenantId(),
                        event.nodeInstanceId(),
                        node.version(),
                        node.dispatchToken(),
                        node.leaseGeneration(),
                        atUtc(now));
        requireOne(changed, "Worker fenced node claim");
        UUID executionRowId = WorkerTaskIds.generate();
        UUID executionId = WorkerTaskIds.generate();
        jdbc.update(
                "INSERT INTO public.worker_lease (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, lease_generation, worker_id, status, acquired_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                WorkerTaskIds.generate(),
                event.tenantId(),
                workerInstanceId,
                workerInstanceId,
                event.nodeInstanceId(),
                node.leaseGeneration(),
                node.dispatchToken(),
                atUtc(now),
                atUtc(node.leaseExpiresAt()));
        jdbc.update(
                "INSERT INTO public.task_execution (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, attempt_no, execution_id, status, started_at, "
                        + "input_ref, input_digest) VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?)",
                executionRowId,
                event.tenantId(),
                workerInstanceId,
                workerInstanceId,
                event.nodeInstanceId(),
                nextAttempt,
                executionId,
                atUtc(now),
                node.inputReference(),
                node.inputDigest());
        insertAuditAndOutbox(
                event, workerInstanceId, "DISPATCHED", RUNNING, "claim", "WORKER_CLAIMED", now);
        return ClaimResult.claimed(
                new ClaimedTask(
                        event,
                        consumerName,
                        node.workflowInstanceId(),
                        executionId,
                        node.nodeId(),
                        node.nodeType(),
                        nextAttempt,
                        node.inputPayload(),
                        node.inputReference(),
                        node.inputDigest(),
                        node.configSnapshot(),
                        node.dispatchToken(),
                        node.leaseGeneration(),
                        node.leaseExpiresAt()));
    }

    private void heartbeatInTransaction(
            ClaimedTask task, String workerInstanceId, Duration extension) {
        setTenant(task.event().tenantId());
        Instant now = transactionTimestamp();
        List<OffsetDateTime> leaseExpiries =
                jdbc.query(
                        "SELECT lease_expire_at FROM public.node_instance WHERE tenant_id = ? "
                                + "AND id = ? AND status = 'RUNNING' AND current_worker_id = ? "
                                + "AND lease_generation = ? AND lease_expire_at > ? FOR UPDATE",
                        (result, rowNumber) ->
                                result.getObject("lease_expire_at", OffsetDateTime.class),
                        task.event().tenantId(),
                        task.event().nodeInstanceId(),
                        task.dispatchToken(),
                        task.leaseGeneration(),
                        atUtc(now));
        if (leaseExpiries.size() != ONE_ROW) {
            throw new IllegalStateException("Worker heartbeat node fence is no longer current");
        }
        Instant nextExpiry =
                Objects.requireNonNull(leaseExpiries.getFirst(), "current lease expiry")
                        .toInstant()
                        .plus(extension);
        int nodeChanged =
                jdbc.update(
                        "UPDATE public.node_instance SET lease_expire_at = ?, updated_at = ?, "
                                + "updated_by = ?, version = version + 1 WHERE tenant_id = ? AND id = ? "
                                + "AND status = 'RUNNING' AND current_worker_id = ? "
                                + "AND lease_generation = ? AND lease_expire_at > ?",
                        atUtc(nextExpiry),
                        atUtc(now),
                        workerInstanceId,
                        task.event().tenantId(),
                        task.event().nodeInstanceId(),
                        task.dispatchToken(),
                        task.leaseGeneration(),
                        atUtc(now));
        requireOne(nodeChanged, "Worker heartbeat node fence");
        int leaseChanged =
                jdbc.update(
                        "UPDATE public.worker_lease SET expires_at = ?, last_heartbeat_at = ?, "
                                + "updated_at = ?, updated_by = ?, version = version + 1 "
                                + "WHERE tenant_id = ? AND node_instance_id = ? AND status = 'ACTIVE' "
                                + "AND worker_id = ? AND lease_generation = ?",
                        atUtc(nextExpiry),
                        atUtc(now),
                        atUtc(now),
                        workerInstanceId,
                        task.event().tenantId(),
                        task.event().nodeInstanceId(),
                        task.dispatchToken(),
                        task.leaseGeneration());
        requireOne(leaseChanged, "Worker heartbeat lease fence");
    }

    private void completeInTransaction(
            ClaimedTask task, String workerInstanceId, NodeExecutionResult result) {
        setTenant(task.event().tenantId());
        Instant now = transactionTimestamp();
        NodeExecutionResult completionResult = planRetry(task, redact(result), now);
        CompletionState completion = CompletionState.from(completionResult);
        int nodeChanged =
                jdbc.update(
                        "UPDATE public.node_instance SET status = ?, next_schedule_at = ?, "
                                + "current_worker_id = NULL, lease_expire_at = NULL, output_ref = ?, "
                                + "output_digest = ?, error_code = ?, updated_at = ?, updated_by = ?, "
                                + "version = version + 1 WHERE tenant_id = ? AND id = ? "
                                + "AND status = 'RUNNING' AND current_worker_id = ? "
                                + "AND lease_generation = ? AND lease_expire_at > ?",
                        completion.nodeStatus(),
                        completionResult.nextScheduleAt() == null
                                ? null
                                : atUtc(completionResult.nextScheduleAt()),
                        completionResult.outputReference(),
                        completionResult.outputDigest(),
                        completionResult.errorCode(),
                        atUtc(now),
                        workerInstanceId,
                        task.event().tenantId(),
                        task.event().nodeInstanceId(),
                        task.dispatchToken(),
                        task.leaseGeneration(),
                        atUtc(now));
        requireOne(nodeChanged, "Worker result node fence");
        int attemptChanged =
                jdbc.update(
                        "UPDATE public.task_execution SET status = ?, finished_at = ?, "
                                + "output_ref = ?, output_digest = ?, error_code = ?, "
                                + "error_summary_redacted = ?, updated_at = ?, updated_by = ?, "
                                + "version = version + 1 WHERE tenant_id = ? AND execution_id = ? "
                                + "AND status = 'RUNNING'",
                        completion.attemptStatus(),
                        atUtc(now),
                        completionResult.outputReference(),
                        completionResult.outputDigest(),
                        completion.attemptErrorCode(completionResult),
                        completion.attemptErrorSummary(completionResult),
                        atUtc(now),
                        workerInstanceId,
                        task.event().tenantId(),
                        task.executionId());
        requireOne(attemptChanged, "Worker attempt finalization");
        int leaseChanged =
                jdbc.update(
                        "UPDATE public.worker_lease SET status = 'RELEASED', released_at = ?, "
                                + "updated_at = ?, updated_by = ?, version = version + 1 "
                                + "WHERE tenant_id = ? AND node_instance_id = ? AND status = 'ACTIVE' "
                                + "AND worker_id = ? AND lease_generation = ?",
                        atUtc(now),
                        atUtc(now),
                        workerInstanceId,
                        task.event().tenantId(),
                        task.event().nodeInstanceId(),
                        task.dispatchToken(),
                        task.leaseGeneration());
        requireOne(leaseChanged, "Worker lease release");
        if (FAILED.equals(completion.nodeStatus())) {
            insertDeadLetter(task, workerInstanceId, completionResult);
        }
        insertProcessedEvent(task, workerInstanceId, completionResult, now);
        insertAuditAndOutbox(
                task.event(),
                workerInstanceId,
                RUNNING,
                completion.nodeStatus(),
                completion.command(),
                completion.reasonCode(),
                now);
    }

    private NodeExecutionResult planRetry(
            ClaimedTask task, NodeExecutionResult result, Instant now) {
        if (result.outcome() != NodeOutcome.FAILED || !result.retryable()) {
            return result;
        }
        OffsetDateTime firstStarted =
                jdbc.queryForObject(
                        "SELECT min(started_at) FROM public.task_execution WHERE tenant_id = ? "
                                + "AND node_instance_id = ?",
                        OffsetDateTime.class,
                        task.event().tenantId(),
                        task.event().nodeInstanceId());
        return retryPlanner.plan(
                task,
                result,
                Objects.requireNonNull(firstStarted, "first attempt start").toInstant(),
                now);
    }

    private static NodeExecutionResult redact(NodeExecutionResult result) {
        if (result.errorSummaryRedacted() == null) {
            return result;
        }
        return new NodeExecutionResult(
                result.outcome(),
                result.outputReference(),
                result.outputDigest(),
                result.errorCode(),
                SensitiveTextRedactor.redact(result.errorSummaryRedacted(), 1000),
                result.retryable(),
                result.nextScheduleAt(),
                result.retryAfter());
    }

    private void insertDeadLetter(
            ClaimedTask task, String workerInstanceId, NodeExecutionResult result) {
        UUID executionRowId =
                jdbc.queryForObject(
                        "SELECT id FROM public.task_execution WHERE tenant_id = ? "
                                + "AND execution_id = ?",
                        UUID.class,
                        task.event().tenantId(),
                        task.executionId());
        String payloadReference = task.inputReference();
        jdbc.update(
                "INSERT INTO public.dead_letter (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, task_execution_id, category, error_code, "
                        + "error_summary_redacted, payload_ref, payload_digest, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'WORKER_EXECUTION', ?, ?, ?, ?, 'OPEN')",
                WorkerTaskIds.generate(),
                task.event().tenantId(),
                workerInstanceId,
                workerInstanceId,
                task.event().nodeInstanceId(),
                executionRowId,
                "WORKER_RUNTIME_" + result.errorCode(),
                result.errorSummaryRedacted(),
                payloadReference,
                payloadReference == null ? null : task.inputDigest());
    }

    private boolean processed(String consumerName, UUID eventId) {
        Integer processedCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.processed_event "
                                + "WHERE consumer_name = ? AND event_id = ?",
                        Integer.class,
                        consumerName,
                        eventId);
        return processedCount != null && processedCount > 0;
    }

    private NodeRow lockNode(UUID tenantId, UUID nodeInstanceId) {
        List<NodeRow> rows =
                jdbc.query(
                        "SELECT n.node_id, n.node_type, n.status, n.version, n.attempt_no, "
                                + "n.current_worker_id, n.lease_generation, n.lease_expire_at, "
                                + "n.config_snapshot::text, i.id AS workflow_instance_id, "
                                + "i.input_payload::text, i.input_ref, i.input_digest "
                                + "FROM public.node_instance n JOIN public.workflow_instance i "
                                + "ON i.tenant_id = n.tenant_id AND i.id = n.workflow_instance_id "
                                + "WHERE n.tenant_id = ? AND n.id = ? FOR UPDATE OF n",
                        (result, rowNumber) ->
                                new NodeRow(
                                        result.getObject("workflow_instance_id", UUID.class),
                                        result.getString("node_id"),
                                        result.getString("node_type"),
                                        result.getString("status"),
                                        result.getLong("version"),
                                        result.getInt("attempt_no"),
                                        result.getString("current_worker_id"),
                                        result.getLong("lease_generation"),
                                        toInstant(
                                                result.getObject(
                                                        "lease_expire_at", OffsetDateTime.class)),
                                        result.getString("input_payload"),
                                        result.getString("input_ref"),
                                        result.getString("input_digest"),
                                        result.getString("config_snapshot")),
                        tenantId,
                        nodeInstanceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertProcessedEvent(
            ClaimedTask task,
            String workerInstanceId,
            NodeExecutionResult result,
            Instant processedAt) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.processed_event (id, tenant_id, created_by, consumer_name, "
                                + "event_id, event_type, schema_version, processed_at, result_digest) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (consumer_name, event_id) DO NOTHING",
                        WorkerTaskIds.generate(),
                        task.event().tenantId(),
                        workerInstanceId,
                        task.consumerName(),
                        task.event().eventId(),
                        task.event().eventType(),
                        task.event().schemaVersion(),
                        atUtc(processedAt),
                        result.outputDigest() == null
                                ? sha256(result.outcome() + ":" + result.errorCode())
                                : result.outputDigest());
        requireOne(inserted, "processed-event completion insert");
    }

    private void insertAuditAndOutbox(
            TaskReadyEvent event,
            String workerInstanceId,
            String fromStatus,
            String toStatus,
            String command,
            String reason,
            Instant occurredAt) {
        UUID eventId = WorkerTaskIds.generate();
        String eventType = "workflow.runtime.node." + command.replace('_', '.');
        ObjectNode before = objectMapper.createObjectNode().put("status", fromStatus);
        ObjectNode after = objectMapper.createObjectNode().put("status", toStatus);
        after.put("command", command);
        jdbc.update(
                "INSERT INTO public.audit_log (id, tenant_id, created_by, actor_type, actor_id, "
                        + "action, resource_type, resource_id, occurred_at, reason, trace_id, "
                        + "before_digest_or_redacted, after_digest_or_redacted) VALUES (?, ?, ?, "
                        + "'WORKER', ?, ?, 'node_instance', ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                eventId,
                event.tenantId(),
                workerInstanceId,
                workerInstanceId,
                "runtime.node." + command,
                event.nodeInstanceId().toString(),
                atUtc(occurredAt),
                reason,
                event.traceId(),
                json(before),
                json(after));
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("tenantId", event.tenantId().toString());
        envelope.put("traceId", event.traceId());
        envelope.put("schemaVersion", 1);
        envelope.put("producer", "worker");
        envelope.put("subject", "node/" + event.nodeInstanceId());
        ObjectNode payload = envelope.putObject("payload");
        payload.put("entityType", "node");
        payload.put("entityId", event.nodeInstanceId().toString());
        payload.put("command", command);
        payload.put("fromStatus", fromStatus);
        payload.put("toStatus", toStatus);
        payload.put("reasonCode", reason);
        String serialized = json(envelope);
        jdbc.update(
                "INSERT INTO public.outbox_event (id, tenant_id, created_by, aggregate_type, "
                        + "aggregate_id, event_type, schema_version, partition_key, payload, "
                        + "payload_digest, occurred_at, trace_context, next_publish_at) VALUES "
                        + "(?, ?, ?, 'node_instance', ?, ?, 1, ?, CAST(? AS jsonb), ?, ?, ?, ?)",
                eventId,
                event.tenantId(),
                workerInstanceId,
                event.nodeInstanceId(),
                eventType,
                event.tenantId() + ":" + event.nodeInstanceId(),
                serialized,
                sha256(serialized),
                atUtc(occurredAt),
                "00-" + event.traceId() + "-0000000000000001-01",
                atUtc(occurredAt));
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT public.app_set_tenant_context(?)", Object.class, tenantId);
    }

    private Instant transactionTimestamp() {
        OffsetDateTime value =
                jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
        return Objects.requireNonNull(value, "transaction timestamp").toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Worker event cannot be serialized", exception);
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

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? Instant.EPOCH : value.toInstant();
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

    private record NodeRow(
            UUID workflowInstanceId,
            String nodeId,
            String nodeType,
            String status,
            long version,
            int attemptNo,
            String dispatchToken,
            long leaseGeneration,
            Instant leaseExpiresAt,
            String inputPayload,
            String inputReference,
            String inputDigest,
            String configSnapshot) {}

    private record CompletionState(
            String nodeStatus, String attemptStatus, String command, String reasonCode) {

        static CompletionState from(NodeExecutionResult result) {
            return switch (result.outcome()) {
                case SUCCEEDED ->
                        new CompletionState(
                                "SUCCEEDED", "SUCCEEDED", "complete_success", "NODE_SUCCEEDED");
                case FAILED ->
                        new CompletionState(FAILED, FAILED, "complete_failure", "NODE_FAILED");
                case RETRY_WAIT ->
                        new CompletionState(
                                "RETRY_WAIT", FAILED, "schedule_retry", "NODE_RETRY_SCHEDULED");
                case WAITING_TIMER ->
                        new CompletionState(
                                "WAITING_TIMER", "SUCCEEDED", "wait_timer", "NODE_TIMER_SCHEDULED");
            };
        }

        String attemptErrorCode(NodeExecutionResult result) {
            return FAILED.equals(attemptStatus) ? result.errorCode() : null;
        }

        String attemptErrorSummary(NodeExecutionResult result) {
            return FAILED.equals(attemptStatus) ? result.errorSummaryRedacted() : null;
        }
    }

    private static final class TaskNotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TaskNotReadyException(String message) {
            super(message);
        }
    }
}
