package com.workflowplatform.controlplane.runtime.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.contracts.expression.DeterministicConditionEvaluator;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.DagProgressor;
import com.workflowplatform.controlplane.runtime.RuntimeEntityType;
import com.workflowplatform.controlplane.runtime.RuntimeSchedulingStore;
import com.workflowplatform.controlplane.runtime.RuntimeStateTransitionService;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionActor;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionCommand;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionEffects;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation using row locks and SKIP LOCKED for scheduler concurrency. */
@Component
final class JdbcRuntimeSchedulingStore implements RuntimeSchedulingStore {

    private static final String READY = "READY";
    private static final String RUNNING = "RUNNING";
    private static final String PAUSE_REQUESTED = "PAUSE_REQUESTED";
    private static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    private static final String LEASE_EXPIRED = "WORKER_LEASE_EXPIRED";
    private static final String STATUS_COLUMN = "status";
    private static final String VERSION_COLUMN = "version";
    private static final int ONE_ROW = 1;
    private static final String CONDITION_NODE_TYPE = "condition";
    private static final String ALWAYS_TRANSITION = "always";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RuntimeStateTransitionService transitions;
    private final ObjectMapper objectMapper;
    private final DagProgressor progressor = new DagProgressor();
    private final DeterministicConditionEvaluator conditionEvaluator =
            new DeterministicConditionEvaluator();

    JdbcRuntimeSchedulingStore(
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
    public ProgressResult progress(
            WorkflowRequestContext context, UUID instanceId, String traceId) {
        ProgressResult result =
                transactions.execute(status -> progressInTransaction(context, instanceId, traceId));
        if (result == null) {
            throw new IllegalStateException("DAG progress transaction returned no result");
        }
        return result;
    }

    @Override
    public List<DispatchResult> dispatchDue(
            WorkflowRequestContext context,
            String schedulerId,
            Instant dueAt,
            int limit,
            Duration leaseDuration,
            String traceId) {
        List<DispatchResult> result =
                transactions.execute(
                        status ->
                                dispatchInTransaction(
                                        context,
                                        schedulerId,
                                        dueAt,
                                        limit,
                                        leaseDuration,
                                        traceId));
        if (result == null) {
            throw new IllegalStateException("due-task dispatch transaction returned no result");
        }
        return List.copyOf(result);
    }

    @Override
    public List<LeaseRecoveryResult> recoverExpired(
            WorkflowRequestContext context,
            String schedulerId,
            Instant expiredAt,
            int limit,
            String traceId) {
        List<LeaseRecoveryResult> result =
                transactions.execute(
                        status ->
                                recoverExpiredInTransaction(
                                        context, schedulerId, expiredAt, limit, traceId));
        if (result == null) {
            throw new IllegalStateException("lease recovery transaction returned no result");
        }
        return List.copyOf(result);
    }

    private ProgressResult progressInTransaction(
            WorkflowRequestContext context, UUID instanceId, String traceId) {
        JdbcRuntimeSupport.setTenantContext(jdbc, context);
        InstanceRow instance = lockInstance(context, instanceId);
        List<NodeRow> rows = lockNodes(context, instanceId);
        ProgressResult controlResult = reconcileControlState(context, instance, rows, traceId);
        if (controlResult != null) {
            return controlResult;
        }
        if (!"CREATED".equals(instance.status()) && !RUNNING.equals(instance.status())) {
            throw new IllegalStateException("only active workflow instances may progress");
        }
        Map<String, NodeRow> byNodeId = new HashMap<>();
        List<DagProgressor.Node> nodes = new ArrayList<>();
        for (NodeRow row : rows) {
            byNodeId.put(row.nodeId(), row);
            nodes.add(new DagProgressor.Node(row.nodeId(), row.status()));
        }
        List<DagProgressor.Decision> decisions =
                progressor.evaluate(nodes, parseEdges(instance.dsl(), instance.inputPayload()));
        boolean starts =
                "CREATED".equals(instance.status())
                        && decisions.stream()
                                .anyMatch(
                                        decision ->
                                                decision.type()
                                                        == DagProgressor.DecisionType.READY);
        if (starts) {
            transitions.transition(
                    command(
                            context,
                            RuntimeEntityType.INSTANCE,
                            instanceId,
                            "start",
                            "CREATED",
                            instance.version(),
                            RUNNING,
                            RuntimeTransitionActor.RUNTIME,
                            "DAG_STARTED",
                            traceId,
                            RuntimeTransitionEffects.none()));
        }
        Instant now = JdbcRuntimeSupport.transactionTimestamp(jdbc);
        int ready = 0;
        int cancelled = 0;
        for (DagProgressor.Decision decision : decisions) {
            NodeRow row = byNodeId.get(decision.nodeId());
            if (decision.type() == DagProgressor.DecisionType.READY) {
                transitions.transition(
                        command(
                                context,
                                RuntimeEntityType.NODE,
                                row.id(),
                                "make_ready",
                                "PENDING",
                                row.version(),
                                READY,
                                RuntimeTransitionActor.RUNTIME,
                                "PREDECESSORS_SATISFIED",
                                traceId,
                                scheduled(now)));
                ready++;
            } else if (decision.type() == DagProgressor.DecisionType.CANCEL) {
                transitions.transition(
                        command(
                                context,
                                RuntimeEntityType.NODE,
                                row.id(),
                                "cancel_pending",
                                "PENDING",
                                row.version(),
                                "CANCELLED",
                                RuntimeTransitionActor.RUNTIME,
                                "PREDECESSOR_TERMINAL_FAILURE",
                                traceId,
                                RuntimeTransitionEffects.none()));
                cancelled++;
            }
        }
        convergeInstance(context, instance, starts, traceId);
        return new ProgressResult(instanceId, starts, ready, cancelled);
    }

    private ProgressResult reconcileControlState(
            WorkflowRequestContext context,
            InstanceRow instance,
            List<NodeRow> rows,
            String traceId) {
        if (PAUSE_REQUESTED.equals(instance.status())) {
            if (rows.stream()
                    .noneMatch(row -> RuntimeControlStateSupport.isActiveExecution(row.status()))) {
                transitions.transition(
                        command(
                                context,
                                RuntimeEntityType.INSTANCE,
                                instance.id(),
                                "pause",
                                PAUSE_REQUESTED,
                                instance.version(),
                                "PAUSED",
                                RuntimeTransitionActor.RUNTIME,
                                "PAUSE_SAFE_POINT_REACHED",
                                traceId,
                                RuntimeTransitionEffects.none()));
            }
            return new ProgressResult(instance.id(), false, 0, 0);
        }
        if (!CANCEL_REQUESTED.equals(instance.status())) {
            return null;
        }
        int running = 0;
        int cancelled = 0;
        for (NodeRow row : rows) {
            String cancellation = RuntimeControlStateSupport.cancellationCommand(row.status());
            if (cancellation != null) {
                transitions.transition(
                        command(
                                context,
                                RuntimeEntityType.NODE,
                                row.id(),
                                cancellation,
                                row.status(),
                                row.version(),
                                "CANCELLED",
                                RuntimeTransitionActor.SCHEDULER,
                                "COOPERATIVE_CANCEL_NOT_STARTED",
                                traceId,
                                RuntimeTransitionEffects.none()));
                cancelled++;
            } else if (RUNNING.equals(row.status())) {
                running++;
            }
        }
        if (running == 0) {
            transitions.transition(
                    command(
                            context,
                            RuntimeEntityType.INSTANCE,
                            instance.id(),
                            "cancel",
                            CANCEL_REQUESTED,
                            instance.version(),
                            "CANCELLED",
                            RuntimeTransitionActor.RUNTIME,
                            "COOPERATIVE_CANCEL_SETTLED",
                            traceId,
                            RuntimeTransitionEffects.none()));
        }
        return new ProgressResult(instance.id(), false, 0, cancelled);
    }

    private void convergeInstance(
            WorkflowRequestContext context,
            InstanceRow instance,
            boolean startedInThisTransaction,
            String traceId) {
        if (!RUNNING.equals(instance.status()) && !startedInThisTransaction) {
            return;
        }
        NodeAggregate aggregate =
                jdbc.queryForObject(
                        "SELECT count(*) AS total, "
                                + "count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded, "
                                + "count(*) FILTER (WHERE status = 'FAILED') AS failed, "
                                + "count(*) FILTER (WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')) "
                                + "AS terminal FROM public.node_instance WHERE tenant_id = ? "
                                + "AND workflow_instance_id = ?",
                        (result, rowNumber) ->
                                new NodeAggregate(
                                        result.getLong("total"),
                                        result.getLong("succeeded"),
                                        result.getLong("failed"),
                                        result.getLong("terminal")),
                        context.tenantId(),
                        instance.id());
        if (aggregate == null
                || aggregate.total() == 0
                || aggregate.terminal() != aggregate.total()) {
            return;
        }
        long expectedVersion = instance.version() + (startedInThisTransaction ? 1 : 0);
        if (aggregate.failed() == 0) {
            transitions.transition(
                    command(
                            context,
                            RuntimeEntityType.INSTANCE,
                            instance.id(),
                            "complete_success",
                            RUNNING,
                            expectedVersion,
                            "SUCCEEDED",
                            RuntimeTransitionActor.RUNTIME,
                            "ALL_SELECTED_NODES_SUCCEEDED",
                            traceId,
                            RuntimeTransitionEffects.none()));
        } else if (aggregate.failed() > 0) {
            transitions.transition(
                    command(
                            context,
                            RuntimeEntityType.INSTANCE,
                            instance.id(),
                            "complete_failure",
                            RUNNING,
                            expectedVersion,
                            "FAILED",
                            RuntimeTransitionActor.RUNTIME,
                            "NODE_TERMINAL_FAILURE",
                            traceId,
                            RuntimeTransitionEffects.noneWithError(
                                    "NODE_TERMINAL_FAILURE", "one or more workflow nodes failed")));
        }
    }

    private List<DispatchResult> dispatchInTransaction(
            WorkflowRequestContext context,
            String schedulerId,
            Instant dueAt,
            int limit,
            Duration leaseDuration,
            String traceId) {
        JdbcRuntimeSupport.setTenantContext(jdbc, context);
        List<NodeRow> due =
                jdbc.query(
                        "SELECT n.id, n.node_id, n.status, n.version, n.lease_generation "
                                + "FROM public.node_instance n JOIN public.workflow_instance i "
                                + "ON i.tenant_id = n.tenant_id AND i.id = n.workflow_instance_id "
                                + "WHERE n.tenant_id = ? AND i.status = 'RUNNING' "
                                + "AND n.status IN ('READY','RETRY_WAIT','WAITING_TIMER') "
                                + "AND n.next_schedule_at <= ? ORDER BY n.next_schedule_at, n.id "
                                + "LIMIT ? FOR UPDATE OF n SKIP LOCKED",
                        (result, rowNumber) ->
                                new NodeRow(
                                        result.getObject("id", UUID.class),
                                        result.getString("node_id"),
                                        result.getString(STATUS_COLUMN),
                                        result.getLong(VERSION_COLUMN),
                                        result.getLong("lease_generation")),
                        context.tenantId(),
                        atUtc(dueAt),
                        limit);
        Instant transactionTime = JdbcRuntimeSupport.transactionTimestamp(jdbc);
        Instant leaseExpiresAt = transactionTime.plus(leaseDuration);
        List<DispatchResult> dispatched = new ArrayList<>();
        for (NodeRow row : due) {
            long readyVersion = row.version();
            if (!READY.equals(row.status())) {
                String command = "RETRY_WAIT".equals(row.status()) ? "retry_due" : "timer_due";
                readyVersion =
                        transitions
                                .transition(
                                        command(
                                                context,
                                                RuntimeEntityType.NODE,
                                                row.id(),
                                                command,
                                                row.status(),
                                                row.version(),
                                                READY,
                                                RuntimeTransitionActor.SCHEDULER,
                                                "SCHEDULE_DUE",
                                                traceId,
                                                scheduled(transactionTime)))
                                .version();
            }
            String dispatchToken = "dispatch:" + schedulerId + ":" + TimeOrderedUuidV7.generate();
            transitions.transition(
                    command(
                            context,
                            RuntimeEntityType.NODE,
                            row.id(),
                            "dispatch",
                            READY,
                            readyVersion,
                            "DISPATCHED",
                            RuntimeTransitionActor.SCHEDULER,
                            "TASK_DISPATCHED",
                            traceId,
                            dispatched(dispatchToken, leaseExpiresAt)));
            dispatched.add(
                    new DispatchResult(
                            row.id(), dispatchToken, row.leaseGeneration() + 1, leaseExpiresAt));
        }
        return dispatched;
    }

    private List<LeaseRecoveryResult> recoverExpiredInTransaction(
            WorkflowRequestContext context,
            String schedulerId,
            Instant expiredAt,
            int limit,
            String traceId) {
        JdbcRuntimeSupport.setTenantContext(jdbc, context);
        WorkflowRequestContext schedulerContext =
                new WorkflowRequestContext(context.tenantId(), schedulerId);
        List<ExpiredLeaseRow> expired =
                jdbc.query(
                        "SELECT n.id, n.status, n.version, n.dispatch_count, n.attempt_no, "
                                + "n.lease_generation FROM public.node_instance n "
                                + "JOIN public.workflow_instance i ON i.tenant_id = n.tenant_id "
                                + "AND i.id = n.workflow_instance_id WHERE n.tenant_id = ? "
                                + "AND i.status IN ('RUNNING','PAUSED','PAUSE_REQUESTED') "
                                + "AND n.status IN ('DISPATCHED','RUNNING') "
                                + "AND n.lease_expire_at <= ? ORDER BY n.lease_expire_at, n.id "
                                + "LIMIT ? FOR UPDATE OF n SKIP LOCKED",
                        (result, rowNumber) ->
                                new ExpiredLeaseRow(
                                        result.getObject("id", UUID.class),
                                        result.getString(STATUS_COLUMN),
                                        result.getLong(VERSION_COLUMN),
                                        result.getInt("dispatch_count"),
                                        result.getInt("attempt_no"),
                                        result.getLong("lease_generation")),
                        context.tenantId(),
                        atUtc(expiredAt),
                        limit);
        Instant transactionTime = JdbcRuntimeSupport.transactionTimestamp(jdbc);
        List<LeaseRecoveryResult> recovered = new ArrayList<>();
        for (ExpiredLeaseRow row : expired) {
            boolean abandoned = RUNNING.equals(row.status());
            if (abandoned) {
                abandonRunningAttempt(schedulerContext, row, traceId);
                expireActiveLease(schedulerContext, row, expiredAt, transactionTime);
            }
            transitions.transition(
                    command(
                            schedulerContext,
                            RuntimeEntityType.NODE,
                            row.id(),
                            "lease_expired",
                            row.status(),
                            row.version(),
                            READY,
                            RuntimeTransitionActor.SCHEDULER,
                            LEASE_EXPIRED,
                            traceId,
                            scheduled(transactionTime)));
            recovered.add(
                    new LeaseRecoveryResult(
                            row.id(),
                            row.status(),
                            abandoned,
                            row.dispatchCount(),
                            row.attemptNo(),
                            row.leaseGeneration() + 1));
        }
        return recovered;
    }

    private void abandonRunningAttempt(
            WorkflowRequestContext context, ExpiredLeaseRow row, String traceId) {
        AttemptRow attempt =
                jdbc
                        .query(
                                "SELECT id, version FROM public.task_execution WHERE tenant_id = ? "
                                        + "AND node_instance_id = ? AND status = 'RUNNING' "
                                        + "FOR UPDATE",
                                (result, rowNumber) ->
                                        new AttemptRow(
                                                result.getObject("id", UUID.class),
                                                result.getLong(VERSION_COLUMN)),
                                context.tenantId(),
                                row.id())
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "running leased node has no active attempt"));
        transitions.transition(
                command(
                        context,
                        RuntimeEntityType.ATTEMPT,
                        attempt.id(),
                        "abandon",
                        RUNNING,
                        attempt.version(),
                        "ABANDONED",
                        RuntimeTransitionActor.SCHEDULER,
                        LEASE_EXPIRED,
                        traceId,
                        RuntimeTransitionEffects.noneWithError(
                                LEASE_EXPIRED,
                                "worker lease expired before a result was committed")));
    }

    private void expireActiveLease(
            WorkflowRequestContext context,
            ExpiredLeaseRow row,
            Instant expiredAt,
            Instant transactionTime) {
        int changed =
                jdbc.update(
                        "UPDATE public.worker_lease SET status = 'EXPIRED', released_at = ?, "
                                + "updated_at = ?, updated_by = ?, version = version + 1 "
                                + "WHERE tenant_id = ? AND node_instance_id = ? "
                                + "AND lease_generation = ? AND status = 'ACTIVE' "
                                + "AND expires_at <= ?",
                        atUtc(transactionTime),
                        atUtc(transactionTime),
                        context.actorId(),
                        context.tenantId(),
                        row.id(),
                        row.leaseGeneration(),
                        atUtc(expiredAt));
        if (changed != ONE_ROW) {
            throw new IllegalStateException("expired running node has no current active lease");
        }
    }

    private InstanceRow lockInstance(WorkflowRequestContext context, UUID instanceId) {
        return jdbc
                .query(
                        "SELECT i.id, i.status, i.version, i.input_payload, v.dsl "
                                + "FROM public.workflow_instance i "
                                + "JOIN public.workflow_version v ON v.tenant_id = i.tenant_id "
                                + "AND v.id = i.workflow_version_id WHERE i.tenant_id = ? "
                                + "AND i.id = ? FOR UPDATE OF i",
                        (result, rowNumber) ->
                                new InstanceRow(
                                        result.getObject("id", UUID.class),
                                        result.getString(STATUS_COLUMN),
                                        result.getLong(VERSION_COLUMN),
                                        result.getString("input_payload"),
                                        result.getString("dsl")),
                        context.tenantId(),
                        instanceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workflow instance not found"));
    }

    private List<NodeRow> lockNodes(WorkflowRequestContext context, UUID instanceId) {
        return jdbc.query(
                "SELECT id, node_id, status, version, lease_generation FROM public.node_instance "
                        + "WHERE tenant_id = ? AND workflow_instance_id = ? "
                        + "ORDER BY node_id FOR UPDATE",
                (result, rowNumber) ->
                        new NodeRow(
                                result.getObject("id", UUID.class),
                                result.getString("node_id"),
                                result.getString(STATUS_COLUMN),
                                result.getLong(VERSION_COLUMN),
                                result.getLong("lease_generation")),
                context.tenantId(),
                instanceId);
    }

    private List<DagProgressor.Edge> parseEdges(String dsl, String inputPayload) {
        try {
            JsonNode definition = objectMapper.readTree(dsl);
            JsonNode edges = definition.path("edges");
            JsonNode input =
                    inputPayload == null
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(inputPayload);
            Map<String, String> nodeTypes = new HashMap<>();
            for (JsonNode node : definition.path("nodes")) {
                nodeTypes.put(node.path("id").asText(), node.path("type").asText());
            }
            Map<String, String> selectedConditionEdges =
                    selectConditionEdges(edges, nodeTypes, input);
            List<DagProgressor.Edge> result = new ArrayList<>();
            for (JsonNode edge : edges) {
                String from = edge.path("from").asText();
                boolean active =
                        !CONDITION_NODE_TYPE.equals(nodeTypes.get(from))
                                || edge.path("id")
                                        .asText()
                                        .equals(selectedConditionEdges.get(from));
                result.add(new DagProgressor.Edge(from, edge.path("to").asText(), active));
            }
            return result;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new IllegalStateException("published workflow edges cannot be read", exception);
        }
    }

    private Map<String, String> selectConditionEdges(
            JsonNode edges, Map<String, String> nodeTypes, JsonNode input) {
        Map<String, String> selected = new HashMap<>();
        Map<String, String> fallbacks = new HashMap<>();
        for (JsonNode edge : edges) {
            String from = edge.path("from").asText();
            if (!CONDITION_NODE_TYPE.equals(nodeTypes.get(from))) {
                continue;
            }
            String transition = edge.path("on").asText();
            if (ALWAYS_TRANSITION.equals(transition)) {
                fallbacks.putIfAbsent(from, edge.path("id").asText());
            } else if ("condition".equals(transition)
                    && !selected.containsKey(from)
                    && conditionEvaluator.evaluate(edge.path("when").asText(), input)) {
                selected.put(from, edge.path("id").asText());
            }
        }
        for (Map.Entry<String, String> fallback : fallbacks.entrySet()) {
            selected.putIfAbsent(fallback.getKey(), fallback.getValue());
        }
        return selected;
    }

    private static RuntimeTransitionCommand command(
            WorkflowRequestContext context,
            RuntimeEntityType entityType,
            UUID entityId,
            String command,
            String expectedStatus,
            long expectedVersion,
            String targetStatus,
            RuntimeTransitionActor actor,
            String reason,
            String traceId,
            RuntimeTransitionEffects effects) {
        return new RuntimeTransitionCommand(
                context,
                entityType,
                entityId,
                command,
                expectedStatus,
                expectedVersion,
                targetStatus,
                actor,
                reason,
                traceId,
                effects);
    }

    private static RuntimeTransitionEffects scheduled(Instant nextScheduleAt) {
        return new RuntimeTransitionEffects(
                nextScheduleAt, null, null, null, null, null, null, null, null);
    }

    private static RuntimeTransitionEffects dispatched(
            String dispatchToken, Instant leaseExpiresAt) {
        return new RuntimeTransitionEffects(
                null, dispatchToken, leaseExpiresAt, null, null, null, null, null, null);
    }

    private static OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record InstanceRow(
            UUID id, String status, long version, String inputPayload, String dsl) {}

    private record NodeAggregate(long total, long succeeded, long failed, long terminal) {}

    private record NodeRow(
            UUID id, String nodeId, String status, long version, long leaseGeneration) {}

    private record ExpiredLeaseRow(
            UUID id,
            String status,
            long version,
            int dispatchCount,
            int attemptNo,
            long leaseGeneration) {}

    private record AttemptRow(UUID id, long version) {}
}
