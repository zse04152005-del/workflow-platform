package com.workflowplatform.controlplane.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.database.PostgresTestRoleProvisioner;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionResult;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionService;
import com.workflowplatform.controlplane.runtime.RuntimeControlResult;
import com.workflowplatform.controlplane.runtime.RuntimeControlService;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlAction;
import com.workflowplatform.controlplane.runtime.RuntimeSchedulingService;
import com.workflowplatform.controlplane.runtime.RuntimeStateTransitionService;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class JdbcRuntimeControlStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g5_control";
    private static final String ADMIN_USER = "workflow_g5_control_admin";
    private static final String MIGRATION_USER = "workflow_g5_control_migration";
    private static final String APPLICATION_USER = "workflow_g5_control_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_g5_control_platform_admin";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d001");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d002");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d003");
    private static final UUID DEFINITION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d004");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d005");
    private static final UUID PAUSE_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d010");
    private static final UUID PAUSE_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d011");
    private static final UUID PAUSE_TIMER_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d012");
    private static final UUID ACTIVE_PAUSE_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d020");
    private static final UUID ACTIVE_PAUSE_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d021");
    private static final UUID CANCEL_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d030");
    private static final UUID CANCEL_RUNNING_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d031");
    private static final UUID CANCEL_ATTEMPT_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d032");
    private static final UUID CANCEL_EXECUTION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d033");
    private static final UUID REPLAY_SOURCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d040");
    private static final UUID REPLAY_UPSTREAM_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d041");
    private static final UUID REPLAY_FAILED_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d042");
    private static final UUID REPLAY_DOWNSTREAM_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d043");
    private static final UUID REPLAY_ATTEMPT_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d044");
    private static final UUID REPLAY_EXECUTION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d045");
    private static final UUID DEAD_LETTER_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d046");
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";
    private static final String DIGEST = "a".repeat(64);
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ONE, "g5-control-actor");
    private static final WorkflowRequestContext OTHER_CONTEXT =
            new WorkflowRequestContext(TENANT_TWO, "g5-control-other-tenant");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G5-control")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static JdbcTemplate admin;
    private static RuntimeControlService control;
    private static RuntimeSchedulingService scheduler;
    private static DeadLetterDispositionService deadLetters;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        PostgresTestRoleProvisioner.provisionAndMigrate(
                POSTGRES,
                DATABASE,
                MIGRATION_USER,
                MIGRATION_CREDENTIAL,
                APPLICATION_USER,
                APPLICATION_CREDENTIAL,
                PLATFORM_ADMIN_ROLE);
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_CREDENTIAL));
        DriverManagerDataSource applicationDataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), APPLICATION_USER, APPLICATION_CREDENTIAL);
        JdbcTemplate application = new JdbcTemplate(applicationDataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(applicationDataSource);
        ObjectMapper mapper = new ObjectMapper();
        RuntimeStateTransitionService transitions =
                new RuntimeStateTransitionService(
                        new JdbcRuntimeTransitionStore(application, transactions, mapper));
        RuntimeControlStore runtimeControlStore =
                new JdbcRuntimeControlStore(application, transactions, transitions, mapper);
        control = new RuntimeControlService(runtimeControlStore);
        deadLetters =
                new DeadLetterDispositionService(
                        new JdbcDeadLetterDispositionStore(
                                application, transactions, runtimeControlStore, mapper));
        scheduler =
                new RuntimeSchedulingService(
                        new JdbcRuntimeSchedulingStore(
                                application, transactions, transitions, mapper));
        seedFixtures();
    }

    @Test
    @Order(1)
    void pausesAtASafePointAndReplaysTheFirstResponseIdempotently() {
        RuntimeControlResult first =
                command(
                        PAUSE_INSTANCE_ID,
                        RuntimeControlAction.PAUSE,
                        "planned maintenance",
                        null,
                        "pause-safe-001");
        RuntimeControlResult replay =
                command(
                        PAUSE_INSTANCE_ID,
                        RuntimeControlAction.PAUSE,
                        "planned maintenance",
                        null,
                        "pause-safe-001");

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.actionId(), replay.actionId());
        assertEquals(first.acceptedAt(), replay.acceptedAt());
        assertEquals("PAUSED", status("workflow_instance", PAUSE_INSTANCE_ID));
        assertEquals("READY", status("node_instance", PAUSE_NODE_ID));
        assertEquals("WAITING_TIMER", status("node_instance", PAUSE_TIMER_NODE_ID));
        admin.update(
                "UPDATE public.node_instance SET next_schedule_at = transaction_timestamp(), "
                        + "updated_at = transaction_timestamp() WHERE id = ?",
                PAUSE_NODE_ID);
        scheduler.dispatchDue(
                CONTEXT,
                "g5-control-scheduler",
                scheduleAt(PAUSE_NODE_ID).plusSeconds(1),
                10,
                Duration.ofMinutes(2),
                TRACE_ID);
        assertEquals("READY", status("node_instance", PAUSE_NODE_ID));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.audit_log WHERE resource_id = ? "
                                + "AND action IN ('runtime.instance.request_pause','runtime.instance.pause')",
                        PAUSE_INSTANCE_ID.toString()));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type IN ('workflow.runtime.instance.request.pause',"
                                + "'workflow.runtime.instance.pause')",
                        PAUSE_INSTANCE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ? AND status = 'COMPLETED' "
                                + "AND response_status = 202",
                        TENANT_ONE,
                        "runtime:control:pause:" + PAUSE_INSTANCE_ID));

        ApiProblemException conflict =
                assertThrows(
                        ApiProblemException.class,
                        () ->
                                command(
                                        PAUSE_INSTANCE_ID,
                                        RuntimeControlAction.PAUSE,
                                        "different body",
                                        null,
                                        "pause-safe-001"));
        assertEquals(ProblemCode.COMMON_CONFLICT_STATE, conflict.problemCode());
    }

    @Test
    @Order(2)
    void resumesAndRestoresDispatchEligibility() {
        RuntimeControlResult result =
                command(
                        PAUSE_INSTANCE_ID,
                        RuntimeControlAction.RESUME,
                        "maintenance complete",
                        null,
                        "resume-safe-001");
        scheduler.dispatchDue(
                CONTEXT,
                "g5-control-resume",
                scheduleAt(PAUSE_NODE_ID).plusSeconds(1),
                10,
                Duration.ofMinutes(2),
                TRACE_ID);

        assertEquals("RUNNING", result.instanceStatus());
        assertEquals("RUNNING", status("workflow_instance", PAUSE_INSTANCE_ID));
        assertEquals("DISPATCHED", status("node_instance", PAUSE_NODE_ID));
    }

    @Test
    @Order(3)
    void convergesAnInFlightPauseAfterTheWorkerReachesASafePoint() {
        RuntimeControlResult requested =
                command(
                        ACTIVE_PAUSE_INSTANCE_ID,
                        RuntimeControlAction.PAUSE,
                        "pause after active node",
                        null,
                        "pause-active-001");
        assertEquals("PAUSE_REQUESTED", requested.instanceStatus());

        admin.update(
                "UPDATE public.node_instance SET status = 'SUCCEEDED', "
                        + "updated_at = transaction_timestamp(), updated_by = 'worker-fixture', "
                        + "version = version + 1, current_worker_id = NULL, lease_expire_at = NULL, "
                        + "output_ref = 'payload://active-pause-output', output_digest = ? WHERE id = ?",
                DIGEST,
                ACTIVE_PAUSE_NODE_ID);
        scheduler.progress(CONTEXT, ACTIVE_PAUSE_INSTANCE_ID, TRACE_ID);

        assertEquals("PAUSED", status("workflow_instance", ACTIVE_PAUSE_INSTANCE_ID));
        assertEquals("SUCCEEDED", status("node_instance", ACTIVE_PAUSE_NODE_ID));
    }

    @Test
    @Order(4)
    void cooperativelyCancelsNewRetryStateWithoutRewritingAttemptHistory() {
        String beforeRequest = attemptSnapshot(CANCEL_ATTEMPT_ID);
        RuntimeControlResult requested =
                command(
                        CANCEL_INSTANCE_ID,
                        RuntimeControlAction.CANCEL,
                        "operator cancellation",
                        null,
                        "cancel-safe-001");

        assertEquals("CANCEL_REQUESTED", requested.instanceStatus());
        assertEquals(beforeRequest, attemptSnapshot(CANCEL_ATTEMPT_ID));
        assertEquals(
                6,
                count(
                        "SELECT count(*) FROM public.node_instance WHERE workflow_instance_id = ? "
                                + "AND status = 'CANCELLED'",
                        CANCEL_INSTANCE_ID));
        assertEquals("RUNNING", status("node_instance", CANCEL_RUNNING_NODE_ID));

        admin.update(
                "UPDATE public.task_execution SET status = 'FAILED', "
                        + "updated_at = transaction_timestamp(), updated_by = 'worker-fixture', "
                        + "version = version + 1, finished_at = transaction_timestamp(), "
                        + "error_code = 'HTTP_TIMEOUT', error_summary_redacted = 'upstream timeout' "
                        + "WHERE id = ?",
                CANCEL_ATTEMPT_ID);
        admin.update(
                "UPDATE public.node_instance SET status = 'RETRY_WAIT', "
                        + "updated_at = transaction_timestamp(), updated_by = 'worker-fixture', "
                        + "version = version + 1, next_schedule_at = transaction_timestamp() "
                        + "+ interval '1 hour', current_worker_id = NULL, lease_expire_at = NULL, "
                        + "error_code = 'HTTP_TIMEOUT' WHERE id = ?",
                CANCEL_RUNNING_NODE_ID);
        String afterWorker = attemptSnapshot(CANCEL_ATTEMPT_ID);
        scheduler.progress(CONTEXT, CANCEL_INSTANCE_ID, TRACE_ID);

        assertEquals("CANCELLED", status("workflow_instance", CANCEL_INSTANCE_ID));
        assertEquals("CANCELLED", status("node_instance", CANCEL_RUNNING_NODE_ID));
        assertEquals(afterWorker, attemptSnapshot(CANCEL_ATTEMPT_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.task_execution WHERE id = ? "
                                + "AND status = 'FAILED' AND error_code = 'HTTP_TIMEOUT'",
                        CANCEL_ATTEMPT_ID));
    }

    @Test
    @Order(5)
    void hidesAnotherTenantsInstanceAndRollsBackTheIdempotencyClaim() {
        ApiProblemException notFound =
                assertThrows(
                        ApiProblemException.class,
                        () ->
                                control.control(
                                        OTHER_CONTEXT,
                                        ACTIVE_PAUSE_INSTANCE_ID,
                                        RuntimeControlAction.RESUME,
                                        "cross tenant probe",
                                        null,
                                        "cross-tenant-001",
                                        TRACE_ID));

        assertEquals(ProblemCode.COMMON_NOT_FOUND_RESOURCE, notFound.problemCode());
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ?",
                        TENANT_TWO,
                        "runtime:control:resume:" + ACTIVE_PAUSE_INSTANCE_ID));
    }

    @Test
    @Order(6)
    void createsBothReplayModesWithoutMutatingSourceAttempts() {
        String attemptBefore = attemptSnapshot(REPLAY_ATTEMPT_ID);
        int attemptCountBefore =
                count(
                        "SELECT count(*) FROM public.task_execution t JOIN public.node_instance n "
                                + "ON n.id = t.node_instance_id WHERE n.workflow_instance_id = ?",
                        REPLAY_SOURCE_ID);

        RuntimeControlResult fullReplay =
                command(
                        REPLAY_SOURCE_ID,
                        RuntimeControlAction.REPLAY,
                        "full replay",
                        null,
                        "replay-full-001");
        RuntimeControlResult failedNodeReplay =
                command(
                        REPLAY_SOURCE_ID,
                        RuntimeControlAction.REPLAY,
                        "retry failed branch",
                        "failed_node",
                        "replay-node-001");

        assertEquals("CREATED", fullReplay.instanceStatus());
        assertEquals(
                3,
                count(
                        "SELECT count(*) FROM public.node_instance WHERE workflow_instance_id = ? "
                                + "AND status = 'PENDING'",
                        fullReplay.targetInstanceId()));
        assertEquals("RUNNING", failedNodeReplay.instanceStatus());
        assertEquals("SUCCEEDED", nodeStatus(failedNodeReplay.targetInstanceId(), "upstream"));
        assertEquals("READY", nodeStatus(failedNodeReplay.targetInstanceId(), "failed_node"));
        assertEquals("PENDING", nodeStatus(failedNodeReplay.targetInstanceId(), "downstream"));
        assertEquals(
                "payload://replay-upstream-output",
                admin.queryForObject(
                        "SELECT output_ref FROM public.node_instance WHERE workflow_instance_id = ? "
                                + "AND node_id = 'upstream'",
                        String.class,
                        failedNodeReplay.targetInstanceId()));
        assertEquals(attemptBefore, attemptSnapshot(REPLAY_ATTEMPT_ID));
        assertEquals(
                attemptCountBefore,
                count(
                        "SELECT count(*) FROM public.task_execution t JOIN public.node_instance n "
                                + "ON n.id = t.node_instance_id WHERE n.workflow_instance_id = ?",
                        REPLAY_SOURCE_ID));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id IN "
                                + "(SELECT id FROM public.node_instance WHERE workflow_instance_id = ? "
                                + "UNION SELECT CAST(? AS uuid)) AND event_type IN "
                                + "('workflow.runtime.instance.replay.accepted','workflow.task.ready')",
                        failedNodeReplay.targetInstanceId(),
                        failedNodeReplay.targetInstanceId()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.audit_log WHERE resource_id = ? "
                                + "AND action = 'runtime.instance.replay'",
                        failedNodeReplay.targetInstanceId().toString()));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ? AND status = 'COMPLETED'",
                        TENANT_ONE,
                        "runtime:control:replay:" + REPLAY_SOURCE_ID));
    }

    @Test
    @Order(7)
    void appendsARedactedDeadLetterNoteIdempotentlyAndHidesCrossTenantRows() {
        DeadLetterDispositionResult first =
                deadLetters.addNote(
                        CONTEXT,
                        DEAD_LETTER_ID,
                        "password=do-not-store investigate downstream timeout",
                        "dead-note-safe-001",
                        TRACE_ID);
        DeadLetterDispositionResult replay =
                deadLetters.addNote(
                        CONTEXT,
                        DEAD_LETTER_ID,
                        "password=do-not-store investigate downstream timeout",
                        "dead-note-safe-001",
                        TRACE_ID);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.actionId(), replay.actionId());
        assertEquals("OPEN", status("dead_letter", DEAD_LETTER_ID));
        assertEquals(
                "password=<redacted> investigate downstream timeout",
                admin.queryForObject(
                        "SELECT reason FROM public.audit_log WHERE resource_id = ? "
                                + "AND action = 'runtime.dead_letter.note'",
                        String.class,
                        DEAD_LETTER_ID.toString()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.runtime.dead_letter.note.added' "
                                + "AND payload::text NOT LIKE '%do-not-store%'",
                        DEAD_LETTER_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ? AND status = 'COMPLETED'",
                        TENANT_ONE,
                        "runtime:dead-letter:note:" + DEAD_LETTER_ID));

        ApiProblemException crossTenant =
                assertThrows(
                        ApiProblemException.class,
                        () ->
                                deadLetters.addNote(
                                        OTHER_CONTEXT,
                                        DEAD_LETTER_ID,
                                        "cross tenant probe",
                                        "dead-note-cross-001",
                                        TRACE_ID));
        assertEquals(ProblemCode.COMMON_NOT_FOUND_RESOURCE, crossTenant.problemCode());
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ?",
                        TENANT_TWO,
                        "runtime:dead-letter:note:" + DEAD_LETTER_ID));
    }

    @Test
    @Order(8)
    void redispatchesAsAFailedNodeReplayWithoutRewritingSourceHistory() {
        String attemptBefore = attemptSnapshot(REPLAY_ATTEMPT_ID);
        DeadLetterDispositionResult first =
                deadLetters.redispatch(
                        CONTEXT,
                        DEAD_LETTER_ID,
                        "operator approved retry",
                        "dead-redispatch-001",
                        TRACE_ID);
        DeadLetterDispositionResult replay =
                deadLetters.redispatch(
                        CONTEXT,
                        DEAD_LETTER_ID,
                        "operator approved retry",
                        "dead-redispatch-001",
                        TRACE_ID);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.actionId(), replay.actionId());
        assertEquals(first.targetInstanceId(), replay.targetInstanceId());
        assertEquals("REQUEUED", first.deadLetterStatus());
        assertEquals("RUNNING", status("workflow_instance", first.targetInstanceId()));
        assertEquals("SUCCEEDED", nodeStatus(first.targetInstanceId(), "upstream"));
        assertEquals("READY", nodeStatus(first.targetInstanceId(), "failed_node"));
        assertEquals("PENDING", nodeStatus(first.targetInstanceId(), "downstream"));
        assertEquals(attemptBefore, attemptSnapshot(REPLAY_ATTEMPT_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.dead_letter WHERE id = ? "
                                + "AND status = 'REQUEUED' AND disposition_count = 1 "
                                + "AND last_disposition_at IS NOT NULL",
                        DEAD_LETTER_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.audit_log WHERE resource_id = ? "
                                + "AND action = 'runtime.dead_letter.redispatch' "
                                + "AND reason = 'operator approved retry'",
                        DEAD_LETTER_ID.toString()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.runtime.dead_letter.redispatched' "
                                + "AND payload->'payload'->>'targetInstanceId' = ?",
                        DEAD_LETTER_ID,
                        first.targetInstanceId().toString()));

        ApiProblemException disposed =
                assertThrows(
                        ApiProblemException.class,
                        () ->
                                deadLetters.redispatch(
                                        CONTEXT,
                                        DEAD_LETTER_ID,
                                        "second disposition",
                                        "dead-redispatch-002",
                                        TRACE_ID));
        assertEquals(ProblemCode.COMMON_CONFLICT_STATE, disposed.problemCode());
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND operation_scope = ? AND status = 'PROCESSING'",
                        TENANT_ONE,
                        "runtime:dead-letter:redispatch:" + DEAD_LETTER_ID));
    }

    private static RuntimeControlResult command(
            UUID instanceId,
            RuntimeControlAction action,
            String reason,
            String fromNodeId,
            String key) {
        return control.control(CONTEXT, instanceId, action, reason, fromNodeId, key, TRACE_ID);
    }

    private static String status(String table, UUID id) {
        return admin.queryForObject(
                "SELECT status FROM public." + table + " WHERE id = ?", String.class, id);
    }

    private static String nodeStatus(UUID instanceId, String nodeId) {
        return admin.queryForObject(
                "SELECT status FROM public.node_instance WHERE workflow_instance_id = ? "
                        + "AND node_id = ?",
                String.class,
                instanceId,
                nodeId);
    }

    private static Instant scheduleAt(UUID nodeInstanceId) {
        return admin.queryForObject(
                        "SELECT next_schedule_at FROM public.node_instance WHERE id = ?",
                        OffsetDateTime.class,
                        nodeInstanceId)
                .toInstant();
    }

    private static String attemptSnapshot(UUID attemptId) {
        return admin.queryForObject(
                "SELECT row_to_json(snapshot)::text FROM (SELECT id, tenant_id, created_by, "
                        + "updated_by, version, node_instance_id, attempt_no, execution_id, status, "
                        + "started_at, finished_at, input_ref, input_digest, output_ref, output_digest, "
                        + "error_code, error_summary_redacted FROM public.task_execution WHERE id = ?) "
                        + "AS snapshot",
                String.class,
                attemptId);
    }

    private static int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private static void seedFixtures() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g5-control', 'g5-control', 'tenant-one', 'tenant-one', 'ACTIVE'), "
                        + "(?, 'g5-control', 'g5-control', 'tenant-two', 'tenant-two', 'ACTIVE')",
                TENANT_ONE,
                TENANT_TWO);
        admin.update(
                "INSERT INTO public.project (id, tenant_id, created_by, updated_by, name, "
                        + "normalized_name, status) VALUES (?, ?, 'g5-control', 'g5-control', "
                        + "'control-project', 'control-project', 'ACTIVE')",
                PROJECT_ID,
                TENANT_ONE);
        String dsl =
                "{\"schemaVersion\":1,\"name\":\"control-it\",\"nodes\":["
                        + "{\"id\":\"upstream\",\"type\":\"start\",\"config\":{}},"
                        + "{\"id\":\"failed_node\",\"type\":\"http\",\"config\":{}},"
                        + "{\"id\":\"downstream\",\"type\":\"end\",\"config\":{}}],"
                        + "\"edges\":[{\"from\":\"upstream\",\"to\":\"failed_node\"},"
                        + "{\"from\":\"failed_node\",\"to\":\"downstream\"}]}";
        admin.update(
                "INSERT INTO public.workflow_definition (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, status, draft_dsl, draft_checksum) "
                        + "VALUES (?, ?, 'g5-control', 'g5-control', ?, 'control-it', 'control-it', "
                        + "'ACTIVE', CAST(? AS jsonb), ?)",
                DEFINITION_ID,
                TENANT_ONE,
                PROJECT_ID,
                dsl,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_version (id, tenant_id, created_by, definition_id, "
                        + "version_no, schema_version, dsl, checksum, executor_catalog_version, "
                        + "published_at, published_by) VALUES (?, ?, 'g5-control', ?, 1, 1, "
                        + "CAST(? AS jsonb), ?, '1', transaction_timestamp(), 'g5-control')",
                VERSION_ID,
                TENANT_ONE,
                DEFINITION_ID,
                dsl,
                DIGEST);
        insertInstance(PAUSE_INSTANCE_ID, "RUNNING");
        insertNode(PAUSE_NODE_ID, PAUSE_INSTANCE_ID, "upstream", "start", "READY", null);
        insertNode(
                PAUSE_TIMER_NODE_ID,
                PAUSE_INSTANCE_ID,
                "pause_timer",
                "delay",
                "WAITING_TIMER",
                null);
        insertInstance(ACTIVE_PAUSE_INSTANCE_ID, "RUNNING");
        insertNode(
                ACTIVE_PAUSE_NODE_ID,
                ACTIVE_PAUSE_INSTANCE_ID,
                "upstream",
                "start",
                "RUNNING",
                null);
        insertCancelFixtures();
        insertReplayFixtures();
    }

    private static void insertCancelFixtures() {
        insertInstance(CANCEL_INSTANCE_ID, "RUNNING");
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d034"),
                CANCEL_INSTANCE_ID,
                "pending_node",
                "http",
                "PENDING",
                null);
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d035"),
                CANCEL_INSTANCE_ID,
                "ready_node",
                "http",
                "READY",
                null);
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d036"),
                CANCEL_INSTANCE_ID,
                "retry_node",
                "http",
                "RETRY_WAIT",
                null);
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d037"),
                CANCEL_INSTANCE_ID,
                "timer_node",
                "delay",
                "WAITING_TIMER",
                null);
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d038"),
                CANCEL_INSTANCE_ID,
                "approval_node",
                "human_task",
                "WAITING_APPROVAL",
                null);
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d039"),
                CANCEL_INSTANCE_ID,
                "dispatched_node",
                "http",
                "DISPATCHED",
                null);
        insertNode(
                CANCEL_RUNNING_NODE_ID,
                CANCEL_INSTANCE_ID,
                "running_node",
                "http",
                "RUNNING",
                null);
        insertNode(
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7d03a"),
                CANCEL_INSTANCE_ID,
                "succeeded_node",
                "start",
                "SUCCEEDED",
                "payload://cancel-succeeded");
        insertAttempt(CANCEL_ATTEMPT_ID, CANCEL_RUNNING_NODE_ID, CANCEL_EXECUTION_ID, "RUNNING");
    }

    private static void insertReplayFixtures() {
        insertInstance(REPLAY_SOURCE_ID, "FAILED");
        insertNode(
                REPLAY_UPSTREAM_NODE_ID,
                REPLAY_SOURCE_ID,
                "upstream",
                "start",
                "SUCCEEDED",
                "payload://replay-upstream-output");
        insertNode(REPLAY_FAILED_NODE_ID, REPLAY_SOURCE_ID, "failed_node", "http", "FAILED", null);
        insertNode(
                REPLAY_DOWNSTREAM_NODE_ID,
                REPLAY_SOURCE_ID,
                "downstream",
                "end",
                "CANCELLED",
                null);
        insertAttempt(REPLAY_ATTEMPT_ID, REPLAY_FAILED_NODE_ID, REPLAY_EXECUTION_ID, "FAILED");
        admin.update(
                "INSERT INTO public.dead_letter (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, task_execution_id, category, error_code, "
                        + "error_summary_redacted, status) VALUES (?, ?, 'g5-control', "
                        + "'g5-control', ?, ?, 'WORKER_EXECUTION', 'WORKER_RUNTIME_FIXTURE', "
                        + "'fixture failure', 'OPEN')",
                DEAD_LETTER_ID,
                TENANT_ONE,
                REPLAY_FAILED_NODE_ID,
                REPLAY_ATTEMPT_ID);
    }

    private static void insertInstance(UUID instanceId, String instanceStatus) {
        boolean terminal =
                "FAILED".equals(instanceStatus)
                        || "SUCCEEDED".equals(instanceStatus)
                        || "CANCELLED".equals(instanceStatus);
        admin.update(
                "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                        + "definition_id, workflow_version_id, status, input_digest, input_payload, "
                        + "started_at, completed_at, trace_id) VALUES (?, ?, 'g5-control', "
                        + "'g5-control', ?, ?, ?, ?, '{}'::jsonb, transaction_timestamp() "
                        + ", CASE WHEN ? THEN transaction_timestamp() ELSE NULL END, ?)",
                instanceId,
                TENANT_ONE,
                DEFINITION_ID,
                VERSION_ID,
                instanceStatus,
                DIGEST,
                terminal,
                TRACE_ID);
    }

    private static void insertNode(
            UUID nodeInstanceId,
            UUID instanceId,
            String nodeId,
            String nodeType,
            String nodeStatus,
            String outputReference) {
        String errorCode =
                "FAILED".equals(nodeStatus) || "RETRY_WAIT".equals(nodeStatus)
                        ? "FIXTURE_FAILURE"
                        : null;
        admin.update(
                "INSERT INTO public.node_instance (id, tenant_id, created_by, updated_by, "
                        + "workflow_instance_id, node_id, node_type, status, next_schedule_at, "
                        + "current_worker_id, lease_expire_at, output_ref, output_digest, error_code, "
                        + "config_snapshot) VALUES (?, ?, 'g5-control', 'g5-control', ?, ?, ?, ?, "
                        + "CASE WHEN ? IN ('READY','RETRY_WAIT','WAITING_TIMER') THEN "
                        + "transaction_timestamp() + interval '1 hour' ELSE NULL END, "
                        + "CASE WHEN ? IN ('DISPATCHED','RUNNING') THEN 'fixture-worker' ELSE NULL END, "
                        + "CASE WHEN ? IN ('DISPATCHED','RUNNING') THEN transaction_timestamp() "
                        + "+ interval '1 hour' ELSE NULL END, ?, ?, ?, '{}'::jsonb)",
                nodeInstanceId,
                TENANT_ONE,
                instanceId,
                nodeId,
                nodeType,
                nodeStatus,
                nodeStatus,
                nodeStatus,
                nodeStatus,
                outputReference,
                outputReference == null ? null : DIGEST,
                errorCode);
    }

    private static void insertAttempt(
            UUID attemptId, UUID nodeInstanceId, UUID executionId, String attemptStatus) {
        boolean running = "RUNNING".equals(attemptStatus);
        admin.update(
                "INSERT INTO public.task_execution (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, attempt_no, execution_id, status, started_at, finished_at, "
                        + "input_digest, error_code, error_summary_redacted) VALUES (?, ?, 'g5-control', "
                        + "'g5-control', ?, 1, ?, 'RUNNING', transaction_timestamp(), NULL, ?, NULL, NULL)",
                attemptId,
                TENANT_ONE,
                nodeInstanceId,
                executionId,
                DIGEST);
        if (!running) {
            admin.update(
                    "UPDATE public.task_execution SET status = ?, "
                            + "updated_at = transaction_timestamp(), updated_by = 'g5-control', "
                            + "version = version + 1, finished_at = transaction_timestamp(), "
                            + "error_code = 'FIXTURE_FAILURE', "
                            + "error_summary_redacted = 'fixture failure' WHERE id = ?",
                    attemptStatus,
                    attemptId);
        }
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
