package com.workflowplatform.controlplane.definition.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.definition.WorkflowDraft;
import com.workflowplatform.controlplane.definition.WorkflowDraftOperationException;
import com.workflowplatform.controlplane.definition.WorkflowDraftStore;
import com.workflowplatform.controlplane.definition.WorkflowPortabilityStore;
import com.workflowplatform.controlplane.definition.WorkflowSnapshot;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation that establishes transaction-local RLS context before every query. */
@Component
final class JdbcWorkflowDraftStore implements WorkflowDraftStore, WorkflowPortabilityStore {

    private static final String WORKFLOW_REFERENCE_PREFIX = "/api/v1/workflows/";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final int EXPECTED_ROW_COUNT = 1;
    private static final int MINIMUM_PUBLISHED_VERSION = 1;
    private static final String WORKFLOW_SELECT =
            "SELECT wd.id, wd.project_id, wd.name, wd.description, wd.status, "
                    + "wd.draft_revision, wd.draft_dsl::text AS draft_dsl, "
                    + "wd.draft_checksum, wd.created_at, wd.updated_at, "
                    + "(SELECT max(wv.version_no) FROM public.workflow_version wv "
                    + "WHERE wv.tenant_id = wd.tenant_id AND wv.definition_id = wd.id) "
                    + "AS published_version FROM public.workflow_definition wd WHERE wd.id = ?";
    private static final RowMapper<WorkflowDraft> WORKFLOW_MAPPER =
            JdbcWorkflowDraftStore::mapWorkflow;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcIdempotencyRecords idempotencyRecords;
    private final ObjectMapper objectMapper;

    JdbcWorkflowDraftStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions = new TransactionTemplate(transactionManager);
        idempotencyRecords = new JdbcIdempotencyRecords(jdbcTemplate);
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public WorkflowDraft create(WorkflowDraftStore.CreateCommand command) {
        Objects.requireNonNull(command, "command");
        WorkflowDraft result =
                transactions.execute(
                        status -> {
                            setTenantContext(command.context().tenantId());
                            JdbcIdempotencyRecords.Claim claim =
                                    claim(command.context(), command.idempotency());
                            if (!claim.owner()) {
                                return replayCreate(claim);
                            }
                            requireActiveProject(command.projectId());
                            insertWorkflow(command);
                            WorkflowDraft created = requireWorkflow(command.workflowId());
                            completeIdempotency(
                                    command.idempotency(),
                                    201,
                                    workflowReference(created.id()),
                                    responseDigest(created));
                            return created;
                        });
        if (result == null) {
            throw new IllegalStateException("Create transaction returned no workflow draft");
        }
        return result;
    }

    @Override
    public WorkflowDraft create(WorkflowPortabilityStore.CreateCommand command) {
        Objects.requireNonNull(command, "command");
        WorkflowDraft result =
                transactions.execute(
                        status -> {
                            setTenantContext(command.context().tenantId());
                            JdbcIdempotencyRecords.Claim claim =
                                    claim(command.context(), command.idempotency());
                            if (!claim.owner()) {
                                return replayCreate(claim);
                            }
                            requireActiveProject(command.projectId());
                            insertWorkflow(
                                    command.context(),
                                    command.workflowId(),
                                    command.projectId(),
                                    command.name(),
                                    command.description(),
                                    command.canonicalDefinition(),
                                    command.checksum());
                            WorkflowDraft created = requireWorkflow(command.workflowId());
                            insertPortabilityAudit(command, created);
                            completeIdempotency(
                                    command.idempotency(),
                                    201,
                                    workflowReference(created.id()),
                                    responseDigest(created));
                            return created;
                        });
        if (result == null) {
            throw new IllegalStateException("Portability create transaction returned no draft");
        }
        return result;
    }

    @Override
    public WorkflowSnapshot load(
            WorkflowRequestContext context, UUID workflowId, Integer publishedVersion) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        WorkflowSnapshot result =
                transactions.execute(
                        status -> {
                            setTenantContext(context.tenantId());
                            return publishedVersion == null
                                    ? loadDraftSnapshot(workflowId)
                                    : loadPublishedSnapshot(workflowId, publishedVersion);
                        });
        if (result == null) {
            throw new IllegalStateException("Portability snapshot transaction returned no result");
        }
        return result;
    }

    @Override
    public Optional<WorkflowDraft> find(WorkflowRequestContext context, UUID workflowId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        Optional<WorkflowDraft> result =
                transactions.execute(
                        status -> {
                            setTenantContext(context.tenantId());
                            return findWorkflow(workflowId);
                        });
        if (result == null) {
            throw new IllegalStateException("Read transaction returned no workflow result");
        }
        return result;
    }

    @Override
    public WorkflowDraft update(UpdateCommand command) {
        Objects.requireNonNull(command, "command");
        WorkflowDraft result =
                transactions.execute(
                        status -> {
                            setTenantContext(command.context().tenantId());
                            JdbcIdempotencyRecords.Claim claim =
                                    claim(command.context(), command.idempotency());
                            if (!claim.owner()) {
                                return replayUpdate(command.workflowId(), claim);
                            }
                            WorkflowDraft current = requireWorkflow(command.workflowId());
                            if (!current.name().equals(command.definitionName())) {
                                throw failure(
                                        WorkflowDraftOperationException.Failure
                                                .DEFINITION_NAME_MISMATCH);
                            }
                            if (current.draftRevision() != command.expectedRevision().revision()
                                    || !current.draftChecksum()
                                            .equals(command.expectedRevision().checksum())) {
                                throw failure(
                                        WorkflowDraftOperationException.Failure
                                                .PRECONDITION_FAILED);
                            }
                            int changed = updateWorkflow(command);
                            if (changed != EXPECTED_ROW_COUNT) {
                                if (findWorkflow(command.workflowId()).isEmpty()) {
                                    throw failure(
                                            WorkflowDraftOperationException.Failure.NOT_FOUND);
                                }
                                throw failure(
                                        WorkflowDraftOperationException.Failure
                                                .PRECONDITION_FAILED);
                            }
                            WorkflowDraft updated = requireWorkflow(command.workflowId());
                            completeIdempotency(
                                    command.idempotency(),
                                    200,
                                    workflowReference(updated.id()),
                                    responseDigest(updated));
                            return updated;
                        });
        if (result == null) {
            throw new IllegalStateException("Update transaction returned no workflow draft");
        }
        return result;
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            try (PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT public.app_set_tenant_context(?)")) {
                                statement.setObject(1, tenantId);
                                statement.execute();
                            }
                            return null;
                        });
    }

    private JdbcIdempotencyRecords.Claim claim(
            WorkflowRequestContext context, IdempotentRequest request) {
        try {
            return idempotencyRecords.claim(
                    context,
                    request.recordId(),
                    request.operationScope(),
                    request.keyDigest(),
                    request.requestDigest());
        } catch (JdbcIdempotencyRecords.ConflictException exception) {
            throw failure(WorkflowDraftOperationException.Failure.CONFLICT, exception);
        }
    }

    private WorkflowDraft replayCreate(JdbcIdempotencyRecords.Claim claim) {
        if (!Integer.valueOf(201).equals(claim.row().responseStatus())) {
            throw failure(WorkflowDraftOperationException.Failure.CONFLICT);
        }
        UUID originalId = workflowIdFromReference(claim.row().responseReference());
        return verifiedReplay(originalId, claim.row());
    }

    private WorkflowDraft replayUpdate(UUID workflowId, JdbcIdempotencyRecords.Claim claim) {
        if (!Integer.valueOf(200).equals(claim.row().responseStatus())
                || !workflowReference(workflowId).equals(claim.row().responseReference())) {
            throw failure(WorkflowDraftOperationException.Failure.CONFLICT);
        }
        return verifiedReplay(workflowId, claim.row());
    }

    private WorkflowDraft verifiedReplay(UUID workflowId, JdbcIdempotencyRecords.Row row) {
        WorkflowDraft current = requireWorkflow(workflowId);
        if (!responseDigest(current).equals(row.responseDigest())) {
            throw failure(WorkflowDraftOperationException.Failure.CONFLICT);
        }
        return current;
    }

    private void requireActiveProject(UUID projectId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.project WHERE id = ? AND status = 'ACTIVE'",
                        Integer.class,
                        projectId);
        if (!Integer.valueOf(1).equals(count)) {
            throw failure(WorkflowDraftOperationException.Failure.NOT_FOUND);
        }
    }

    private void insertWorkflow(WorkflowDraftStore.CreateCommand command) {
        insertWorkflow(
                command.context(),
                command.workflowId(),
                command.projectId(),
                command.name(),
                command.description(),
                command.canonicalDefinition(),
                command.checksum());
    }

    private void insertWorkflow(
            WorkflowRequestContext context,
            UUID workflowId,
            UUID projectId,
            String name,
            String description,
            String canonicalDefinition,
            String checksum) {
        try {
            jdbc.update(
                    "INSERT INTO public.workflow_definition "
                            + "(id, tenant_id, created_by, updated_by, project_id, name, "
                            + "normalized_name, description, status, draft_dsl, draft_revision, "
                            + "draft_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', "
                            + "CAST(? AS jsonb), 1, ?)",
                    workflowId,
                    context.tenantId(),
                    context.actorId(),
                    context.actorId(),
                    projectId,
                    name,
                    name,
                    description,
                    canonicalDefinition,
                    checksum);
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, UNIQUE_VIOLATION_SQL_STATE)) {
                throw failure(WorkflowDraftOperationException.Failure.CONFLICT, exception);
            }
            throw exception;
        }
    }

    private WorkflowSnapshot loadDraftSnapshot(UUID workflowId) {
        List<WorkflowSnapshot> rows =
                jdbc.query(
                        "SELECT id, project_id, name, description, draft_revision, "
                                + "draft_dsl::text AS definition, draft_checksum AS checksum, "
                                + "updated_at AS captured_at FROM public.workflow_definition "
                                + "WHERE id = ?",
                        (result, rowNumber) ->
                                new WorkflowSnapshot(
                                        result.getObject("id", UUID.class),
                                        result.getObject("project_id", UUID.class),
                                        result.getString("name"),
                                        result.getString("description"),
                                        WorkflowSnapshot.DRAFT,
                                        result.getLong("draft_revision"),
                                        null,
                                        result.getString("definition"),
                                        result.getString("checksum"),
                                        instant(result, "captured_at")),
                        workflowId);
        return requireSnapshot(rows);
    }

    private WorkflowSnapshot loadPublishedSnapshot(UUID workflowId, int version) {
        if (version < MINIMUM_PUBLISHED_VERSION) {
            throw failure(WorkflowDraftOperationException.Failure.NOT_FOUND);
        }
        List<WorkflowSnapshot> rows =
                jdbc.query(
                        "SELECT wd.id, wd.project_id, wd.name, wd.description, wv.version_no, "
                                + "wv.dsl::text AS definition, wv.checksum, "
                                + "wv.published_at AS captured_at FROM public.workflow_version wv "
                                + "JOIN public.workflow_definition wd ON wd.tenant_id = wv.tenant_id "
                                + "AND wd.id = wv.definition_id WHERE wd.id = ? "
                                + "AND wv.version_no = ?",
                        (result, rowNumber) ->
                                new WorkflowSnapshot(
                                        result.getObject("id", UUID.class),
                                        result.getObject("project_id", UUID.class),
                                        result.getString("name"),
                                        result.getString("description"),
                                        WorkflowSnapshot.PUBLISHED_VERSION,
                                        null,
                                        result.getInt("version_no"),
                                        result.getString("definition"),
                                        result.getString("checksum"),
                                        instant(result, "captured_at")),
                        workflowId,
                        version);
        return requireSnapshot(rows);
    }

    private WorkflowSnapshot requireSnapshot(List<WorkflowSnapshot> rows) {
        if (rows.size() != EXPECTED_ROW_COUNT) {
            throw failure(WorkflowDraftOperationException.Failure.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private void insertPortabilityAudit(
            WorkflowPortabilityStore.CreateCommand command, WorkflowDraft created) {
        Instant occurredAt = transactionTimestamp();
        ObjectNode details = objectMapper.createObjectNode();
        details.put("targetWorkflowId", created.id().toString());
        details.put("targetChecksum", created.draftChecksum());
        details.put("sourceWorkflowId", command.sourceWorkflowId().toString());
        details.put("sourceKind", command.sourceKind());
        if (command.sourceDraftRevision() != null) {
            details.put("sourceDraftRevision", command.sourceDraftRevision());
        }
        if (command.sourceVersion() != null) {
            details.put("sourceVersion", command.sourceVersion());
        }
        details.put("sourceChecksum", command.sourceChecksum());
        int inserted =
                jdbc.update(
                        "INSERT INTO public.audit_log "
                                + "(id, tenant_id, created_by, actor_type, actor_id, action, "
                                + "resource_type, resource_id, occurred_at, trace_id, "
                                + "after_digest_or_redacted) VALUES (?, ?, ?, 'USER', ?, ?, "
                                + "'workflow_definition', ?, ?, ?, CAST(? AS jsonb))",
                        TimeOrderedUuidV7.generate(),
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        command.auditAction(),
                        created.id().toString(),
                        OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                        command.traceId(),
                        json(details));
        if (inserted != EXPECTED_ROW_COUNT) {
            throw new IllegalStateException("Portability audit insert did not affect one row");
        }
    }

    private Instant transactionTimestamp() {
        OffsetDateTime timestamp =
                jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
        if (timestamp == null) {
            throw new IllegalStateException("Transaction timestamp was not returned");
        }
        return timestamp.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Portability audit could not be serialized", exception);
        }
    }

    private int updateWorkflow(UpdateCommand command) {
        return jdbc.update(
                "UPDATE public.workflow_definition SET draft_dsl = CAST(? AS jsonb), "
                        + "draft_revision = draft_revision + 1, draft_checksum = ?, "
                        + "updated_at = transaction_timestamp(), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND draft_revision = ? "
                        + "AND draft_checksum = ?",
                command.canonicalDefinition(),
                command.checksum(),
                command.context().actorId(),
                command.workflowId(),
                command.expectedRevision().revision(),
                command.expectedRevision().checksum());
    }

    private void completeIdempotency(
            IdempotentRequest request, int status, String reference, String digest) {
        idempotencyRecords.complete(
                request.operationScope(), request.keyDigest(), status, reference, digest);
    }

    private Optional<WorkflowDraft> findWorkflow(UUID workflowId) {
        List<WorkflowDraft> rows = jdbc.query(WORKFLOW_SELECT, WORKFLOW_MAPPER, workflowId);
        if (rows.size() > EXPECTED_ROW_COUNT) {
            throw new IllegalStateException("Workflow identifier resolved more than one row");
        }
        return rows.stream().findFirst();
    }

    private WorkflowDraft requireWorkflow(UUID workflowId) {
        return findWorkflow(workflowId)
                .orElseThrow(() -> failure(WorkflowDraftOperationException.Failure.NOT_FOUND));
    }

    WorkflowDraft lockForPublication(WorkflowRequestContext context, UUID workflowId) {
        WorkflowDraft result =
                transactions.execute(
                        status -> {
                            setTenantContext(context.tenantId());
                            List<WorkflowDraft> rows =
                                    jdbc.query(
                                            WORKFLOW_SELECT + " FOR UPDATE OF wd",
                                            WORKFLOW_MAPPER,
                                            workflowId);
                            if (rows.size() > EXPECTED_ROW_COUNT) {
                                throw new IllegalStateException(
                                        "Workflow identifier resolved more than one row");
                            }
                            return rows.stream()
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    failure(
                                                            WorkflowDraftOperationException.Failure
                                                                    .NOT_FOUND));
                        });
        if (result == null) {
            throw new IllegalStateException("Locked workflow lookup returned no result");
        }
        return result;
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static WorkflowDraft mapWorkflow(ResultSet result, int rowNumber) throws SQLException {
        return new WorkflowDraft(
                result.getObject("id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getString("name"),
                result.getString("description"),
                result.getString("status"),
                result.getLong("draft_revision"),
                (Integer) result.getObject("published_version"),
                result.getString("draft_dsl"),
                result.getString("draft_checksum"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column).toInstant();
    }

    private static String responseDigest(WorkflowDraft draft) {
        return sha256(draft.id() + "|" + draft.draftRevision() + "|" + draft.draftChecksum());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    private static String workflowReference(UUID workflowId) {
        return WORKFLOW_REFERENCE_PREFIX + workflowId;
    }

    private static UUID workflowIdFromReference(String reference) {
        if (reference == null || !reference.startsWith(WORKFLOW_REFERENCE_PREFIX)) {
            throw failure(WorkflowDraftOperationException.Failure.CONFLICT);
        }
        try {
            return UUID.fromString(reference.substring(WORKFLOW_REFERENCE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw failure(WorkflowDraftOperationException.Failure.CONFLICT, exception);
        }
    }

    private static boolean hasSqlState(Throwable throwable, String expectedState) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && expectedState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static WorkflowDraftOperationException failure(
            WorkflowDraftOperationException.Failure failure) {
        return new WorkflowDraftOperationException(failure);
    }

    private static WorkflowDraftOperationException failure(
            WorkflowDraftOperationException.Failure failure, Throwable cause) {
        return new WorkflowDraftOperationException(failure, cause);
    }
}
