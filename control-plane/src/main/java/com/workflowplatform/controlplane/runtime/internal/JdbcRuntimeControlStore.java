package com.workflowplatform.controlplane.runtime.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.LengthPrefixedSha256;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.RuntimeControlResult;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore;
import com.workflowplatform.controlplane.runtime.RuntimeEntityType;
import com.workflowplatform.controlplane.runtime.RuntimeStateTransitionService;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionActor;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionCommand;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionEffects;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionResult;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL control store coupling idempotency, state, audit and Outbox in one transaction. */
@Component
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class JdbcRuntimeControlStore implements RuntimeControlStore {

    private static final int ACCEPTED = 202;
    private static final int ONE_ROW = 1;
    private static final int RESPONSE_REFERENCE_PARTS = 5;
    private static final String RUNNING = "RUNNING";
    private static final String READY = "READY";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    private static final String ACTION_REFERENCE_PREFIX = "runtime-action|";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RuntimeStateTransitionService transitions;
    private final ObjectMapper objectMapper;

    JdbcRuntimeControlStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            RuntimeStateTransitionService transitionService,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        transitions = Objects.requireNonNull(transitionService, "transitionService");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public RuntimeControlResult execute(RuntimeControlCommand command) {
        Objects.requireNonNull(command, "command");
        RuntimeControlResult result = transactions.execute(status -> executeInTransaction(command));
        if (result == null) {
            throw new IllegalStateException("runtime control transaction returned no result");
        }
        return result;
    }

    private RuntimeControlResult executeInTransaction(RuntimeControlCommand command) {
        JdbcRuntimeSupport.setTenantContext(jdbc, command.context());
        IdempotencyClaim claim = claim(command);
        if (!claim.owner()) {
            return replay(claim.row());
        }
        RuntimeControlResult result =
                switch (command.action()) {
                    case PAUSE -> pause(command);
                    case RESUME -> resume(command);
                    case CANCEL -> cancel(command);
                    case REPLAY -> replayInstance(command);
                };
        completeIdempotency(command, result);
        return result;
    }

    private RuntimeControlResult pause(RuntimeControlCommand command) {
        SourceInstance instance = lockInstance(command);
        requireStatus(instance, Set.of(RUNNING));
        RuntimeTransitionResult requested =
                transitionInstance(
                        command,
                        instance,
                        "request_pause",
                        "PAUSE_REQUESTED",
                        "USER_PAUSE_REQUESTED");
        String finalStatus = "PAUSE_REQUESTED";
        if (activeExecutionCount(command.context(), instance.id()) == 0) {
            transitions.transition(
                    transitionCommand(
                            command,
                            RuntimeEntityType.INSTANCE,
                            instance.id(),
                            "pause",
                            "PAUSE_REQUESTED",
                            requested.version(),
                            "PAUSED",
                            "PAUSE_SAFE_POINT_REACHED",
                            RuntimeTransitionEffects.none()));
            finalStatus = "PAUSED";
        }
        return new RuntimeControlResult(
                requested.eventId(), requested.occurredAt(), false, instance.id(), finalStatus);
    }

    private RuntimeControlResult resume(RuntimeControlCommand command) {
        SourceInstance instance = lockInstance(command);
        requireStatus(instance, Set.of("PAUSED"));
        RuntimeTransitionResult resumed =
                transitionInstance(command, instance, "resume", RUNNING, "USER_RESUME_REQUESTED");
        return new RuntimeControlResult(
                resumed.eventId(), resumed.occurredAt(), false, instance.id(), RUNNING);
    }

    private RuntimeControlResult cancel(RuntimeControlCommand command) {
        SourceInstance instance = lockInstance(command);
        requireStatus(instance, Set.of(RUNNING, "PAUSE_REQUESTED", "PAUSED"));
        RuntimeTransitionResult requested =
                transitionInstance(
                        command,
                        instance,
                        "request_cancel",
                        CANCEL_REQUESTED,
                        "USER_COOPERATIVE_CANCEL_REQUESTED");
        int runningNodes = cancelInactiveNodes(command, instance.id());
        String finalStatus = CANCEL_REQUESTED;
        if (runningNodes == 0) {
            transitions.transition(
                    transitionCommand(
                            command,
                            RuntimeEntityType.INSTANCE,
                            instance.id(),
                            "cancel",
                            CANCEL_REQUESTED,
                            requested.version(),
                            "CANCELLED",
                            "COOPERATIVE_CANCEL_SETTLED",
                            RuntimeTransitionEffects.none()));
            finalStatus = "CANCELLED";
        }
        return new RuntimeControlResult(
                requested.eventId(), requested.occurredAt(), false, instance.id(), finalStatus);
    }

    private RuntimeControlResult replayInstance(RuntimeControlCommand command) {
        SourceInstance source = lockInstance(command);
        requireStatus(source, Set.of("CANCELLED", "SUCCEEDED", "FAILED"));
        List<SourceNode> sourceNodes = lockSourceNodes(command.context(), source.id());
        SourceNode replayFrom = findReplayFrom(command.fromNodeId(), sourceNodes);
        Instant acceptedAt = JdbcRuntimeSupport.transactionTimestamp(jdbc);
        UUID targetInstanceId = TimeOrderedUuidV7.generate();
        boolean fromFailedNode = replayFrom != null;
        insertReplayInstance(command, source, targetInstanceId, acceptedAt, fromFailedNode);
        UUID readyNodeId =
                insertReplayNodes(
                        command, source, sourceNodes, replayFrom, targetInstanceId, acceptedAt);
        insertReplayAudit(command, source.id(), targetInstanceId, acceptedAt, fromFailedNode);
        insertOutbox(
                targetInstanceId,
                command,
                "workflow.runtime.instance.replay.accepted",
                "workflow_instance",
                targetInstanceId,
                acceptedAt,
                fromFailedNode ? "failed_node" : "new_instance");
        if (readyNodeId != null) {
            insertOutbox(
                    TimeOrderedUuidV7.generate(),
                    command,
                    "workflow.task.ready",
                    "node_instance",
                    readyNodeId,
                    acceptedAt,
                    "replay_from_failed_node");
        }
        return new RuntimeControlResult(
                targetInstanceId,
                acceptedAt,
                false,
                targetInstanceId,
                fromFailedNode ? RUNNING : "CREATED");
    }

    private RuntimeTransitionResult transitionInstance(
            RuntimeControlCommand command,
            SourceInstance instance,
            String transitionCommand,
            String targetStatus,
            String reasonCode) {
        return transitions.transition(
                transitionCommand(
                        command,
                        RuntimeEntityType.INSTANCE,
                        instance.id(),
                        transitionCommand,
                        instance.status(),
                        instance.version(),
                        targetStatus,
                        reasonCode,
                        RuntimeTransitionEffects.none()));
    }

    private int cancelInactiveNodes(RuntimeControlCommand command, UUID instanceId) {
        List<ControlNode> nodes =
                jdbc.query(
                        "SELECT id, status, version FROM public.node_instance WHERE tenant_id = ? "
                                + "AND workflow_instance_id = ? ORDER BY node_id FOR UPDATE",
                        (result, rowNumber) ->
                                new ControlNode(
                                        result.getObject("id", UUID.class),
                                        result.getString("status"),
                                        result.getLong("version")),
                        command.context().tenantId(),
                        instanceId);
        int running = 0;
        for (ControlNode node : nodes) {
            String cancellation = RuntimeControlStateSupport.cancellationCommand(node.status());
            if (cancellation != null) {
                transitions.transition(
                        transitionCommand(
                                command,
                                RuntimeEntityType.NODE,
                                node.id(),
                                cancellation,
                                node.status(),
                                node.version(),
                                "CANCELLED",
                                "COOPERATIVE_CANCEL_NOT_STARTED",
                                RuntimeTransitionEffects.none()));
            } else if (RUNNING.equals(node.status())) {
                running++;
            }
        }
        return running;
    }

    private int activeExecutionCount(WorkflowRequestContext context, UUID instanceId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.node_instance WHERE tenant_id = ? "
                                + "AND workflow_instance_id = ? "
                                + "AND status IN ('DISPATCHED','RUNNING')",
                        Integer.class,
                        context.tenantId(),
                        instanceId);
        return Objects.requireNonNull(count, "active execution count");
    }

    private SourceInstance lockInstance(RuntimeControlCommand command) {
        return jdbc
                .query(
                        "SELECT i.id, i.status, i.version, i.definition_id, "
                                + "i.workflow_version_id, i.input_ref, i.input_digest, "
                                + "i.input_payload::text, v.dsl::text FROM public.workflow_instance i "
                                + "JOIN public.workflow_version v ON v.tenant_id = i.tenant_id "
                                + "AND v.id = i.workflow_version_id WHERE i.tenant_id = ? "
                                + "AND i.id = ? FOR UPDATE OF i",
                        (result, rowNumber) ->
                                new SourceInstance(
                                        result.getObject("id", UUID.class),
                                        result.getString("status"),
                                        result.getLong("version"),
                                        result.getObject("definition_id", UUID.class),
                                        result.getObject("workflow_version_id", UUID.class),
                                        result.getString("input_ref"),
                                        result.getString("input_digest"),
                                        result.getString("input_payload"),
                                        result.getString("dsl")),
                        command.context().tenantId(),
                        command.instanceId())
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiProblemException.of(ProblemCode.COMMON_NOT_FOUND_RESOURCE));
    }

    private List<SourceNode> lockSourceNodes(WorkflowRequestContext context, UUID instanceId) {
        return jdbc.query(
                "SELECT node_id, node_type, status, config_snapshot::text, output_ref, "
                        + "output_digest FROM public.node_instance WHERE tenant_id = ? "
                        + "AND workflow_instance_id = ? ORDER BY node_id FOR UPDATE",
                (result, rowNumber) ->
                        new SourceNode(
                                result.getString("node_id"),
                                result.getString("node_type"),
                                result.getString("status"),
                                result.getString("config_snapshot"),
                                result.getString("output_ref"),
                                result.getString("output_digest")),
                context.tenantId(),
                instanceId);
    }

    private SourceNode findReplayFrom(String fromNodeId, List<SourceNode> nodes) {
        if (fromNodeId == null) {
            return null;
        }
        SourceNode node =
                nodes.stream()
                        .filter(candidate -> fromNodeId.equals(candidate.nodeId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        ApiProblemException.of(
                                                ProblemCode.COMMON_NOT_FOUND_RESOURCE));
        if (!Set.of("FAILED", "CANCELLED").contains(node.status())) {
            throw ApiProblemException.of(ProblemCode.COMMON_CONFLICT_STATE);
        }
        return node;
    }

    private void insertReplayInstance(
            RuntimeControlCommand command,
            SourceInstance source,
            UUID targetInstanceId,
            Instant acceptedAt,
            boolean fromFailedNode) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                                + "definition_id, workflow_version_id, status, input_ref, input_digest, "
                                + "input_payload, started_at, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                                + "CAST(? AS jsonb), ?, ?)",
                        targetInstanceId,
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        source.definitionId(),
                        source.workflowVersionId(),
                        fromFailedNode ? RUNNING : "CREATED",
                        source.inputReference(),
                        source.inputDigest(),
                        source.inputPayload(),
                        fromFailedNode ? atUtc(acceptedAt) : null,
                        command.traceId());
        requireOne(inserted, "replay workflow instance insert");
    }

    private UUID insertReplayNodes(
            RuntimeControlCommand command,
            SourceInstance source,
            List<SourceNode> sourceNodes,
            SourceNode replayFrom,
            UUID targetInstanceId,
            Instant acceptedAt) {
        Set<String> descendants =
                replayFrom == null ? Set.of() : descendants(source.dsl(), replayFrom.nodeId());
        UUID readyNodeId = null;
        for (SourceNode sourceNode : sourceNodes) {
            ReplayNodeState state = replayNodeState(sourceNode, replayFrom, descendants);
            UUID nodeInstanceId = TimeOrderedUuidV7.generate();
            jdbc.update(
                    "INSERT INTO public.node_instance (id, tenant_id, created_by, updated_by, "
                            + "workflow_instance_id, node_id, node_type, status, next_schedule_at, "
                            + "output_ref, output_digest, config_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, CAST(? AS jsonb))",
                    nodeInstanceId,
                    command.context().tenantId(),
                    command.context().actorId(),
                    command.context().actorId(),
                    targetInstanceId,
                    sourceNode.nodeId(),
                    sourceNode.nodeType(),
                    state.status(),
                    READY.equals(state.status()) ? atUtc(acceptedAt) : null,
                    state.copyOutput() ? sourceNode.outputReference() : null,
                    state.copyOutput() ? sourceNode.outputDigest() : null,
                    sourceNode.configSnapshot());
            if (READY.equals(state.status())) {
                readyNodeId = nodeInstanceId;
            }
        }
        return readyNodeId;
    }

    private static ReplayNodeState replayNodeState(
            SourceNode sourceNode, SourceNode replayFrom, Set<String> descendants) {
        if (replayFrom == null) {
            return new ReplayNodeState("PENDING", false);
        }
        if (sourceNode.nodeId().equals(replayFrom.nodeId())) {
            return new ReplayNodeState(READY, false);
        }
        if (descendants.contains(sourceNode.nodeId())) {
            return new ReplayNodeState("PENDING", false);
        }
        if (SUCCEEDED.equals(sourceNode.status())) {
            return new ReplayNodeState(SUCCEEDED, true);
        }
        return new ReplayNodeState("CANCELLED", false);
    }

    private Set<String> descendants(String dsl, String fromNodeId) {
        try {
            JsonNode definition = objectMapper.readTree(dsl);
            Map<String, List<String>> outgoing = new HashMap<>();
            for (JsonNode edge : definition.path("edges")) {
                appendOutgoing(outgoing, edge.path("from").asText(), edge.path("to").asText());
            }
            Set<String> visited = new HashSet<>();
            ArrayDeque<String> pending = new ArrayDeque<>();
            pending.add(fromNodeId);
            while (!pending.isEmpty()) {
                String node = pending.removeFirst();
                for (String target : outgoing.getOrDefault(node, List.of())) {
                    if (visited.add(target)) {
                        pending.addLast(target);
                    }
                }
            }
            return Set.copyOf(visited);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("published replay graph cannot be read", exception);
        }
    }

    private static void appendOutgoing(
            Map<String, List<String>> outgoing, String sourceNodeId, String targetNodeId) {
        outgoing.computeIfAbsent(sourceNodeId, ignored -> new ArrayList<>()).add(targetNodeId);
    }

    private void insertReplayAudit(
            RuntimeControlCommand command,
            UUID sourceInstanceId,
            UUID targetInstanceId,
            Instant acceptedAt,
            boolean fromFailedNode) {
        ObjectNode before = objectMapper.createObjectNode();
        before.put("sourceInstanceId", sourceInstanceId.toString());
        ObjectNode after = objectMapper.createObjectNode();
        after.put("targetInstanceId", targetInstanceId.toString());
        after.put("mode", fromFailedNode ? "failed_node" : "new_instance");
        after.put("reasonDigest", sha256(command.reason()));
        jdbc.update(
                "INSERT INTO public.audit_log (id, tenant_id, created_by, actor_type, actor_id, "
                        + "action, resource_type, resource_id, occurred_at, reason, trace_id, "
                        + "before_digest_or_redacted, after_digest_or_redacted) VALUES (?, ?, ?, "
                        + "'SYSTEM', ?, 'runtime.instance.replay', 'workflow_instance', ?, ?, "
                        + "'USER_REPLAY_REQUESTED', ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                targetInstanceId,
                command.context().tenantId(),
                command.context().actorId(),
                command.context().actorId(),
                targetInstanceId.toString(),
                atUtc(acceptedAt),
                command.traceId(),
                json(before),
                json(after));
    }

    private void insertOutbox(
            UUID eventId,
            RuntimeControlCommand command,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Instant occurredAt,
            String mode) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("tenantId", command.context().tenantId().toString());
        envelope.put("traceId", command.traceId());
        envelope.put("schemaVersion", 1);
        envelope.put("producer", "control_plane");
        envelope.put("subject", aggregateType + "/" + aggregateId);
        ObjectNode payload = envelope.putObject("payload");
        payload.put("entityType", aggregateType);
        payload.put("entityId", aggregateId.toString());
        payload.put("mode", mode);
        String serialized = json(envelope);
        jdbc.update(
                "INSERT INTO public.outbox_event (id, tenant_id, created_by, aggregate_type, "
                        + "aggregate_id, event_type, schema_version, partition_key, payload, "
                        + "payload_digest, occurred_at, trace_context, next_publish_at) VALUES "
                        + "(?, ?, ?, ?, ?, ?, 1, ?, CAST(? AS jsonb), ?, ?, ?, ?)",
                eventId,
                command.context().tenantId(),
                command.context().actorId(),
                aggregateType,
                aggregateId,
                eventType,
                command.context().tenantId() + ":" + aggregateId,
                serialized,
                sha256(serialized),
                atUtc(occurredAt),
                "00-" + command.traceId() + "-0000000000000001-01",
                atUtc(occurredAt));
    }

    private IdempotencyClaim claim(RuntimeControlCommand command) {
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

    private IdempotencyRow lockIdempotency(RuntimeControlCommand command) {
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
                .orElseThrow(() -> new IllegalStateException("control idempotency row is missing"));
    }

    private void resetExpiredIdempotency(RuntimeControlCommand command) {
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
        requireOne(changed, "expired control idempotency reset");
    }

    private void completeIdempotency(RuntimeControlCommand command, RuntimeControlResult result) {
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
                        LengthPrefixedSha256.digest(
                                result.actionId().toString(),
                                result.acceptedAt().toString(),
                                result.targetInstanceId().toString(),
                                result.instanceStatus()),
                        command.context().actorId(),
                        command.context().tenantId(),
                        command.operationScope(),
                        command.keyDigest());
        requireOne(changed, "control idempotency completion");
    }

    private static String reference(RuntimeControlResult result) {
        return ACTION_REFERENCE_PREFIX
                + result.actionId()
                + "|"
                + result.acceptedAt()
                + "|"
                + result.targetInstanceId()
                + "|"
                + result.instanceStatus();
    }

    private static RuntimeControlResult replay(IdempotencyRow row) {
        String reference = row.responseReference();
        if (reference == null || !reference.startsWith(ACTION_REFERENCE_PREFIX)) {
            throw new IllegalStateException("completed control response reference is invalid");
        }
        String[] parts = reference.split("\\|", -1);
        if (parts.length != RESPONSE_REFERENCE_PARTS) {
            throw new IllegalStateException("completed control response reference is malformed");
        }
        return new RuntimeControlResult(
                UUID.fromString(parts[1]),
                Instant.parse(parts[2]),
                true,
                UUID.fromString(parts[3]),
                parts[4]);
    }

    private static RuntimeTransitionCommand transitionCommand(
            RuntimeControlCommand control,
            RuntimeEntityType entityType,
            UUID entityId,
            String action,
            String expectedStatus,
            long expectedVersion,
            String targetStatus,
            String reasonCode,
            RuntimeTransitionEffects effects) {
        return new RuntimeTransitionCommand(
                control.context(),
                entityType,
                entityId,
                action,
                expectedStatus,
                expectedVersion,
                targetStatus,
                RuntimeTransitionActor.RUNTIME,
                reasonCode,
                control.traceId(),
                effects);
    }

    private static void requireStatus(SourceInstance instance, Set<String> allowed) {
        if (!allowed.contains(instance.status())) {
            throw ApiProblemException.of(ProblemCode.COMMON_CONFLICT_STATE);
        }
    }

    private static void requireOne(int changed, String operation) {
        if (changed != ONE_ROW) {
            throw new IllegalStateException(operation + " did not affect one row");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "runtime control event cannot be serialized", exception);
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

    private static OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record IdempotencyClaim(boolean owner, IdempotencyRow row) {}

    private record IdempotencyRow(
            String requestDigest, String status, String responseReference, boolean expired) {}

    private record SourceInstance(
            UUID id,
            String status,
            long version,
            UUID definitionId,
            UUID workflowVersionId,
            String inputReference,
            String inputDigest,
            String inputPayload,
            String dsl) {}

    private record ControlNode(UUID id, String status, long version) {}

    private record SourceNode(
            String nodeId,
            String nodeType,
            String status,
            String configSnapshot,
            String outputReference,
            String outputDigest) {}

    private record ReplayNodeState(String status, boolean copyOutput) {}
}
