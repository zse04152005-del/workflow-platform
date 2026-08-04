package com.workflowplatform.controlplane.runtime.internal;

import com.workflowplatform.controlplane.runtime.RuntimeQueryStore;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL RLS implementation of runtime keyset queries; OFFSET is never used. */
@Component
final class JdbcRuntimeQueryStore implements RuntimeQueryStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcRuntimeQueryStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setReadOnly(true);
    }

    @Override
    public Optional<InstanceView> findInstance(WorkflowRequestContext context, UUID instanceId) {
        return read(
                        context,
                        () ->
                                jdbc.query(
                                        instanceSelect() + " WHERE i.tenant_id = ? AND i.id = ?",
                                        (result, rowNumber) -> instance(result),
                                        context.tenantId(),
                                        instanceId))
                .stream()
                .findFirst();
    }

    @Override
    public List<InstanceView> instances(
            WorkflowRequestContext context, CursorBoundary boundary, int fetchLimit) {
        return read(
                context,
                () -> {
                    List<Object> arguments = new ArrayList<>();
                    arguments.add(context.tenantId());
                    String cursorClause = cursorClause("i", boundary, arguments);
                    arguments.add(fetchLimit);
                    return jdbc.query(
                            instanceSelect()
                                    + " WHERE i.tenant_id = ? "
                                    + cursorClause
                                    + " ORDER BY i.created_at DESC, i.id DESC LIMIT ?",
                            (result, rowNumber) -> instance(result),
                            arguments.toArray());
                });
    }

    @Override
    public List<NodeView> nodes(
            WorkflowRequestContext context,
            UUID instanceId,
            CursorBoundary boundary,
            int fetchLimit) {
        return read(
                context,
                () -> {
                    List<Object> arguments = new ArrayList<>();
                    arguments.add(context.tenantId());
                    arguments.add(instanceId);
                    String cursorClause = cursorClause("n", boundary, arguments);
                    arguments.add(fetchLimit);
                    return jdbc.query(
                            "SELECT n.id, n.workflow_instance_id, n.node_id, n.node_type, n.status, "
                                    + "n.attempt_no, i.input_ref, i.input_digest, n.output_ref, "
                                    + "n.output_digest, n.error_code, n.next_schedule_at, n.created_at "
                                    + "FROM public.node_instance n JOIN public.workflow_instance i "
                                    + "ON i.tenant_id = n.tenant_id AND i.id = n.workflow_instance_id "
                                    + "WHERE n.tenant_id = ? "
                                    + "AND n.workflow_instance_id = ? "
                                    + cursorClause
                                    + " ORDER BY n.created_at DESC, n.id DESC LIMIT ?",
                            (result, rowNumber) ->
                                    new NodeView(
                                            result.getObject("id", UUID.class),
                                            result.getObject("workflow_instance_id", UUID.class),
                                            result.getString("node_id"),
                                            result.getString("node_type"),
                                            result.getString("status"),
                                            result.getInt("attempt_no"),
                                            result.getString("input_ref"),
                                            result.getString("input_digest"),
                                            result.getString("output_ref"),
                                            result.getString("output_digest"),
                                            result.getString("error_code"),
                                            instant(
                                                    result.getObject(
                                                            "next_schedule_at",
                                                            OffsetDateTime.class)),
                                            instant(
                                                    result.getObject(
                                                            "created_at", OffsetDateTime.class))),
                            arguments.toArray());
                });
    }

    @Override
    public List<AttemptView> attempts(
            WorkflowRequestContext context,
            UUID instanceId,
            CursorBoundary boundary,
            int fetchLimit) {
        return read(
                context,
                () -> {
                    List<Object> arguments = new ArrayList<>();
                    arguments.add(context.tenantId());
                    arguments.add(instanceId);
                    String cursorClause = cursorClause("a", boundary, arguments);
                    arguments.add(fetchLimit);
                    return jdbc.query(
                            "SELECT a.id, n.workflow_instance_id, a.node_instance_id, a.attempt_no, "
                                    + "a.execution_id, a.status, a.input_ref, a.input_digest, "
                                    + "a.output_ref, a.output_digest, a.error_code, "
                                    + "a.error_summary_redacted, a.created_at, a.started_at, a.finished_at "
                                    + "FROM public.task_execution a JOIN public.node_instance n "
                                    + "ON n.tenant_id = a.tenant_id AND n.id = a.node_instance_id "
                                    + "WHERE a.tenant_id = ? AND n.workflow_instance_id = ? "
                                    + cursorClause
                                    + " ORDER BY a.created_at DESC, a.id DESC LIMIT ?",
                            (result, rowNumber) ->
                                    new AttemptView(
                                            result.getObject("id", UUID.class),
                                            result.getObject("workflow_instance_id", UUID.class),
                                            result.getObject("node_instance_id", UUID.class),
                                            result.getInt("attempt_no"),
                                            result.getObject("execution_id", UUID.class),
                                            result.getString("status"),
                                            result.getString("input_ref"),
                                            result.getString("input_digest"),
                                            result.getString("output_ref"),
                                            result.getString("output_digest"),
                                            result.getString("error_code"),
                                            result.getString("error_summary_redacted"),
                                            instant(
                                                    result.getObject(
                                                            "created_at", OffsetDateTime.class)),
                                            instant(
                                                    result.getObject(
                                                            "started_at", OffsetDateTime.class)),
                                            instant(
                                                    result.getObject(
                                                            "finished_at", OffsetDateTime.class))),
                            arguments.toArray());
                });
    }

    private <T> T read(WorkflowRequestContext context, java.util.function.Supplier<T> query) {
        T result =
                transactions.execute(
                        status -> {
                            JdbcRuntimeSupport.setTenantContext(jdbc, context);
                            return query.get();
                        });
        return Objects.requireNonNull(result, "runtime query transaction result");
    }

    private static String cursorClause(
            String alias, CursorBoundary boundary, List<Object> arguments) {
        if (boundary == null) {
            return "";
        }
        arguments.add(atUtc(boundary.createdAt()));
        arguments.add(boundary.id());
        return "AND (" + alias + ".created_at, " + alias + ".id) < (?, ?) ";
    }

    private static String instanceSelect() {
        return "SELECT i.id, i.definition_id, i.workflow_version_id, v.version_no, i.status, "
                + "i.business_key, i.input_ref, i.input_digest, i.trace_id, i.created_at, "
                + "i.started_at, i.completed_at FROM public.workflow_instance i "
                + "JOIN public.workflow_version v ON v.tenant_id = i.tenant_id "
                + "AND v.id = i.workflow_version_id";
    }

    private static InstanceView instance(java.sql.ResultSet result) throws java.sql.SQLException {
        return new InstanceView(
                result.getObject("id", UUID.class),
                result.getObject("definition_id", UUID.class),
                result.getObject("workflow_version_id", UUID.class),
                result.getInt("version_no"),
                result.getString("status"),
                result.getString("business_key"),
                result.getString("input_ref"),
                result.getString("input_digest"),
                result.getString("trace_id"),
                instant(result.getObject("created_at", OffsetDateTime.class)),
                instant(result.getObject("started_at", OffsetDateTime.class)),
                instant(result.getObject("completed_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
