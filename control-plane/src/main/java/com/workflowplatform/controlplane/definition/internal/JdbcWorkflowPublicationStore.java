package com.workflowplatform.controlplane.definition.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.dsl.WorkflowTrigger;
import com.workflowplatform.controlplane.definition.PublishedWorkflow;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration;
import com.workflowplatform.controlplane.definition.WorkflowDraft;
import com.workflowplatform.controlplane.definition.WorkflowDraftOperationException;
import com.workflowplatform.controlplane.definition.WorkflowPublicationCandidate;
import com.workflowplatform.controlplane.definition.WorkflowPublicationException;
import com.workflowplatform.controlplane.definition.WorkflowPublicationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL publication transaction spanning revalidation, versioning, audit and Outbox. */
@Component
final class JdbcWorkflowPublicationStore implements WorkflowPublicationStore {

    private static final int EXPECTED_ROW_COUNT = 1;
    private static final int MINIMUM_VERSION = 1;
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String VERSION_REFERENCE_PREFIX = "/api/v1/workflows/";
    private static final String VERSION_REFERENCE_SEGMENT = "/versions/";
    private static final String EVENT_TYPE = "workflow.version.published";

    private final JdbcTemplate jdbc;
    private final JdbcWorkflowDraftStore drafts;
    private final JdbcIdempotencyRecords idempotencyRecords;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    JdbcWorkflowPublicationStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            JdbcWorkflowDraftStore draftStore,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        drafts = Objects.requireNonNull(draftStore, "draftStore");
        idempotencyRecords = new JdbcIdempotencyRecords(jdbcTemplate);
        transactions = new TransactionTemplate(transactionManager);
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public PublishedWorkflow publish(PublishCommand command, PublicationPreparer preparer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(preparer, "preparer");
        PublishedWorkflow result =
                transactions.execute(
                        status -> {
                            setTenantContext(command.context().tenantId());
                            JdbcIdempotencyRecords.Claim claim = claim(command);
                            if (!claim.owner()) {
                                return replay(command.workflowId(), claim.row());
                            }
                            WorkflowDraft locked = lockDraft(command);
                            WorkflowPublicationCandidate candidate =
                                    preparer.prepare(command.context(), locked);
                            PublishedWorkflow published = persist(command, candidate);
                            complete(command.idempotency(), published);
                            return published;
                        });
        if (result == null) {
            throw new IllegalStateException("Publication transaction returned no result");
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

    private JdbcIdempotencyRecords.Claim claim(PublishCommand command) {
        IdempotentRequest request = command.idempotency();
        try {
            return idempotencyRecords.claim(
                    command.context(),
                    request.recordId(),
                    request.operationScope(),
                    request.keyDigest(),
                    request.requestDigest());
        } catch (JdbcIdempotencyRecords.ConflictException exception) {
            throw failure(WorkflowPublicationException.Failure.CONFLICT, exception);
        }
    }

    private WorkflowDraft lockDraft(PublishCommand command) {
        WorkflowDraft locked;
        try {
            locked = drafts.lockForPublication(command.context(), command.workflowId());
        } catch (WorkflowDraftOperationException exception) {
            if (exception.failure() == WorkflowDraftOperationException.Failure.NOT_FOUND) {
                throw failure(WorkflowPublicationException.Failure.NOT_FOUND, exception);
            }
            throw exception;
        }
        if (locked.draftRevision() != command.expectedDraftRevision()) {
            throw failure(WorkflowPublicationException.Failure.REVISION_MISMATCH);
        }
        return locked;
    }

    private PublishedWorkflow persist(
            PublishCommand command, WorkflowPublicationCandidate candidate) {
        if (!command.workflowId().equals(candidate.draft().id())
                || command.expectedDraftRevision() != candidate.draft().draftRevision()) {
            throw new IllegalStateException("Publication candidate changed after draft locking");
        }
        int version = nextVersion(command.workflowId());
        UUID versionId = TimeOrderedUuidV7.generate();
        Instant publishedAt = transactionTimestamp();
        try {
            insertVersion(command, candidate, versionId, version, publishedAt);
            insertTriggers(command, candidate, versionId);
            activateDefinition(command);
            insertAudit(command, candidate, versionId, version, publishedAt);
            insertOutbox(command, candidate, versionId, version, publishedAt);
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, UNIQUE_VIOLATION_SQL_STATE)) {
                throw failure(WorkflowPublicationException.Failure.CONFLICT, exception);
            }
            throw exception;
        }
        return new PublishedWorkflow(
                command.workflowId(), version, candidate.parsed().checksum(), publishedAt);
    }

    private int nextVersion(UUID workflowId) {
        Integer next =
                jdbc.queryForObject(
                        "SELECT COALESCE(max(version_no), 0) + 1 FROM public.workflow_version "
                                + "WHERE definition_id = ?",
                        Integer.class,
                        workflowId);
        if (next == null || next < MINIMUM_VERSION) {
            throw new IllegalStateException("Next workflow version could not be allocated");
        }
        return next;
    }

    private Instant transactionTimestamp() {
        OffsetDateTime timestamp =
                jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
        if (timestamp == null) {
            throw new IllegalStateException("Publication timestamp was not returned");
        }
        return timestamp.toInstant();
    }

    private void insertVersion(
            PublishCommand command,
            WorkflowPublicationCandidate candidate,
            UUID versionId,
            int version,
            Instant publishedAt) {
        int inserted =
                jdbc.update(
                        "INSERT INTO public.workflow_version "
                                + "(id, tenant_id, created_by, definition_id, version_no, "
                                + "schema_version, dsl, checksum, executor_catalog_version, "
                                + "published_at, published_by) VALUES (?, ?, ?, ?, ?, ?, "
                                + "CAST(? AS jsonb), ?, ?, ?, ?)",
                        versionId,
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.workflowId(),
                        version,
                        candidate.parsed().definition().schemaVersion(),
                        candidate.parsed().canonicalJson(),
                        candidate.parsed().checksum(),
                        Integer.toString(candidate.parsed().definition().catalogVersion()),
                        OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC),
                        command.context().actorId());
        requireSingleRow(inserted, "workflow version insert");
    }

    private void insertTriggers(
            PublishCommand command, WorkflowPublicationCandidate candidate, UUID versionId) {
        for (WorkflowTrigger trigger : candidate.parsed().definition().triggers()) {
            ValidatedWorkflowConfiguration.TriggerConfiguration configuration =
                    candidate.configuration().triggers().get(trigger.id());
            if (configuration == null) {
                throw new IllegalStateException("Validated trigger configuration is missing");
            }
            String timezone = null;
            String misfirePolicy = null;
            if (configuration
                    instanceof ValidatedWorkflowConfiguration.CronTriggerConfiguration cron) {
                timezone = cron.timezone().getId();
                misfirePolicy = cron.misfirePolicy().name().toLowerCase(Locale.ROOT);
            }
            int inserted =
                    jdbc.update(
                            "INSERT INTO public.trigger_config "
                                    + "(id, tenant_id, created_by, workflow_version_id, "
                                    + "trigger_key, trigger_type, config, timezone, misfire_policy) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                            TimeOrderedUuidV7.generate(),
                            command.context().tenantId(),
                            command.context().actorId(),
                            versionId,
                            trigger.id(),
                            trigger.type().wireName(),
                            json(trigger.configuration()),
                            timezone,
                            misfirePolicy);
            requireSingleRow(inserted, "trigger configuration insert");
        }
    }

    private void activateDefinition(PublishCommand command) {
        int changed =
                jdbc.update(
                        "UPDATE public.workflow_definition SET status = 'ACTIVE', "
                                + "updated_at = transaction_timestamp(), updated_by = ?, "
                                + "version = version + 1 WHERE id = ? AND draft_revision = ?",
                        command.context().actorId(),
                        command.workflowId(),
                        command.expectedDraftRevision());
        requireSingleRow(changed, "workflow definition activation");
    }

    private void insertAudit(
            PublishCommand command,
            WorkflowPublicationCandidate candidate,
            UUID versionId,
            int version,
            Instant publishedAt) {
        ObjectNode after = publicationDetails(command, candidate, versionId, version);
        int inserted =
                jdbc.update(
                        "INSERT INTO public.audit_log "
                                + "(id, tenant_id, created_by, actor_type, actor_id, action, "
                                + "resource_type, resource_id, occurred_at, reason, trace_id, "
                                + "after_digest_or_redacted) VALUES (?, ?, ?, 'USER', ?, "
                                + "'workflow.version.publish', 'workflow_version', ?, ?, ?, ?, "
                                + "CAST(? AS jsonb))",
                        TimeOrderedUuidV7.generate(),
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.context().actorId(),
                        versionId.toString(),
                        OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC),
                        command.releaseNote(),
                        command.traceId(),
                        json(after));
        requireSingleRow(inserted, "publication audit insert");
    }

    private void insertOutbox(
            PublishCommand command,
            WorkflowPublicationCandidate candidate,
            UUID versionId,
            int version,
            Instant publishedAt) {
        UUID eventId = TimeOrderedUuidV7.generate();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", EVENT_TYPE);
        envelope.put("occurredAt", publishedAt.toString());
        envelope.put("tenantId", command.context().tenantId().toString());
        envelope.put("traceId", command.traceId());
        envelope.put("schemaVersion", 1);
        envelope.put("producer", "control_plane");
        envelope.put("subject", "workflow-definition/" + command.workflowId());
        envelope.put("dataContentType", "application/json");
        envelope.set("payload", publicationDetails(command, candidate, versionId, version));
        String payload = json(envelope);
        int inserted =
                jdbc.update(
                        "INSERT INTO public.outbox_event "
                                + "(id, tenant_id, created_by, aggregate_type, aggregate_id, "
                                + "event_type, schema_version, partition_key, payload, "
                                + "payload_digest, occurred_at, trace_context, next_publish_at) "
                                + "VALUES (?, ?, ?, 'workflow_definition', ?, ?, 1, ?, "
                                + "CAST(? AS jsonb), ?, ?, ?, ?)",
                        eventId,
                        command.context().tenantId(),
                        command.context().actorId(),
                        command.workflowId(),
                        EVENT_TYPE,
                        command.context().tenantId() + ":" + command.workflowId(),
                        payload,
                        sha256(payload),
                        OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC),
                        "00-" + command.traceId() + "-0000000000000001-01",
                        OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC));
        requireSingleRow(inserted, "publication Outbox insert");
    }

    private ObjectNode publicationDetails(
            PublishCommand command,
            WorkflowPublicationCandidate candidate,
            UUID versionId,
            int version) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("workflowId", command.workflowId().toString());
        details.put("workflowVersionId", versionId.toString());
        details.put("version", version);
        details.put("checksum", candidate.parsed().checksum());
        details.put("draftRevision", candidate.draft().draftRevision());
        return details;
    }

    private void complete(IdempotentRequest request, PublishedWorkflow published) {
        idempotencyRecords.complete(
                request.operationScope(),
                request.keyDigest(),
                201,
                versionReference(published.workflowId(), published.version()),
                responseDigest(published));
    }

    private PublishedWorkflow replay(UUID workflowId, JdbcIdempotencyRecords.Row row) {
        if (!Integer.valueOf(201).equals(row.responseStatus())) {
            throw failure(WorkflowPublicationException.Failure.CONFLICT);
        }
        int version = versionFromReference(workflowId, row.responseReference());
        List<PublishedWorkflow> rows =
                jdbc.query(
                        "SELECT definition_id, version_no, checksum, published_at "
                                + "FROM public.workflow_version WHERE definition_id = ? "
                                + "AND version_no = ?",
                        (result, rowNumber) ->
                                new PublishedWorkflow(
                                        result.getObject("definition_id", UUID.class),
                                        result.getInt("version_no"),
                                        result.getString("checksum"),
                                        result.getObject("published_at", OffsetDateTime.class)
                                                .toInstant()),
                        workflowId,
                        version);
        if (rows.size() != EXPECTED_ROW_COUNT
                || !responseDigest(rows.getFirst()).equals(row.responseDigest())) {
            throw failure(WorkflowPublicationException.Failure.CONFLICT);
        }
        return rows.getFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Publication metadata could not be serialized", exception);
        }
    }

    private static void requireSingleRow(int count, String operation) {
        if (count != EXPECTED_ROW_COUNT) {
            throw new IllegalStateException(operation + " did not affect exactly one row");
        }
    }

    private static String versionReference(UUID workflowId, int version) {
        return VERSION_REFERENCE_PREFIX + workflowId + VERSION_REFERENCE_SEGMENT + version;
    }

    private static int versionFromReference(UUID workflowId, String reference) {
        String prefix = VERSION_REFERENCE_PREFIX + workflowId + VERSION_REFERENCE_SEGMENT;
        if (reference == null || !reference.startsWith(prefix)) {
            throw failure(WorkflowPublicationException.Failure.CONFLICT);
        }
        try {
            int version = Integer.parseInt(reference.substring(prefix.length()));
            if (version < MINIMUM_VERSION) {
                throw new NumberFormatException("non-positive version");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw failure(WorkflowPublicationException.Failure.CONFLICT, exception);
        }
    }

    private static String responseDigest(PublishedWorkflow published) {
        return sha256(
                published.workflowId() + "|" + published.version() + "|" + published.checksum());
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

    private static WorkflowPublicationException failure(
            WorkflowPublicationException.Failure failure) {
        return new WorkflowPublicationException(failure);
    }

    private static WorkflowPublicationException failure(
            WorkflowPublicationException.Failure failure, Throwable cause) {
        return new WorkflowPublicationException(failure, cause);
    }
}
