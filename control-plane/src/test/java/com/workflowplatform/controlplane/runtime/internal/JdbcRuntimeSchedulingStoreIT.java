package com.workflowplatform.controlplane.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.database.PostgresTestRoleProvisioner;
import com.workflowplatform.controlplane.runtime.CronSchedulingService;
import com.workflowplatform.controlplane.runtime.CronSchedulingStore;
import com.workflowplatform.controlplane.runtime.LeaseRecoveryService;
import com.workflowplatform.controlplane.runtime.RuntimeCursorPage;
import com.workflowplatform.controlplane.runtime.RuntimeQueryService;
import com.workflowplatform.controlplane.runtime.RuntimeQueryStore;
import com.workflowplatform.controlplane.runtime.RuntimeSchedulingService;
import com.workflowplatform.controlplane.runtime.RuntimeSchedulingStore;
import com.workflowplatform.controlplane.runtime.RuntimeStateTransitionService;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerService;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.payload.PayloadDigests;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.CloseResource",
    "PMD.UnitTestAssertionsShouldIncludeMessage"
})
class JdbcRuntimeSchedulingStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g4_scheduler";
    private static final String ADMIN_USER = "workflow_g4s_admin";
    private static final String MIGRATION_USER = "workflow_g4s_migration";
    private static final String APPLICATION_USER = "workflow_g4s_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_g4s_platform_admin";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c511");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c512");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c513");
    private static final UUID DEFINITION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c514");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c515");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c516");
    private static final UUID DUE_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c517");
    private static final UUID PAUSED_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c50f");
    private static final UUID PAUSED_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c510");
    private static final UUID RECOVERY_DISPATCHED_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c521");
    private static final UUID RECOVERY_RUNNING_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c522");
    private static final UUID RECOVERY_ATTEMPT_ROW_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c523");
    private static final UUID RECOVERY_EXECUTION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c524");
    private static final UUID RECOVERY_LEASE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c525");
    private static final UUID START_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c518");
    private static final UUID END_NODE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c519");
    private static final UUID RETRY_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c51a");
    private static final UUID TIMER_NODE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c51b");
    private static final UUID CRON_TRIGGER_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c51c");
    private static final UUID ATTEMPT_ROW_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c51d");
    private static final UUID EXECUTION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c51e");
    private static final String TRACE_ID = "abcdef1234567890abcdef1234567890";
    private static final String DIGEST = "1".repeat(64);
    private static final String EMPTY_INPUT = "{}";
    private static final String EMPTY_INPUT_DIGEST =
            PayloadDigests.sha256(EMPTY_INPUT.getBytes(StandardCharsets.UTF_8));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G4-05-G4-06")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ONE, "g4-scheduler");
    private static JdbcTemplate admin;
    private static RuntimeSchedulingService service;
    private static LeaseRecoveryService recoveryService;
    private static CronSchedulingService cronService;
    private static RuntimeQueryService queryService;

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
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeStateTransitionService transitionService =
                new RuntimeStateTransitionService(
                        new JdbcRuntimeTransitionStore(application, transactions, objectMapper));
        RuntimeTriggerService triggerService =
                new RuntimeTriggerService(
                        new JdbcRuntimeTriggerStore(application, transactions, objectMapper),
                        objectMapper);
        JdbcRuntimeSchedulingStore schedulingStore =
                new JdbcRuntimeSchedulingStore(
                        application, transactions, transitionService, objectMapper);
        service = new RuntimeSchedulingService(schedulingStore);
        recoveryService = new LeaseRecoveryService(schedulingStore);
        cronService =
                new CronSchedulingService(
                        new JdbcCronSchedulingStore(
                                application,
                                transactions,
                                transitionService,
                                triggerService,
                                objectMapper));
        queryService =
                new RuntimeQueryService(new JdbcRuntimeQueryStore(application, transactions));
        seedFixtures();
    }

    @Test
    @Order(1)
    void startsInstanceAndPersistsOnlyDependencyFreeNodeAsReady() {
        RuntimeSchedulingStore.ProgressResult result =
                service.progress(CONTEXT, INSTANCE_ID, TRACE_ID);

        assertTrue(result.instanceStarted());
        assertEquals(1, result.ready());
        assertEquals(0, result.cancelled());
        assertEquals("RUNNING", status("workflow_instance", INSTANCE_ID));
        assertEquals("READY", status("node_instance", START_NODE_ID));
        assertEquals("PENDING", status("node_instance", END_NODE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.task.ready'",
                        START_NODE_ID));
    }

    @Test
    @Order(2)
    void advancesSuccessorOnlyAfterItsPredecessorSucceeded() {
        admin.update(
                "UPDATE public.node_instance SET status = 'SUCCEEDED', next_schedule_at = NULL, "
                        + "updated_at = transaction_timestamp(), updated_by = 'fixture', "
                        + "version = version + 1 WHERE id = ?",
                START_NODE_ID);

        RuntimeSchedulingStore.ProgressResult result =
                service.progress(CONTEXT, INSTANCE_ID, TRACE_ID);

        assertEquals(1, result.ready());
        assertEquals("READY", status("node_instance", END_NODE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.task.ready'",
                        END_NODE_ID));
    }

    @Test
    @Order(3)
    void concurrentSchedulersDispatchOneDueNodeExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        Instant dueAt = Instant.now().plusSeconds(10);
        List<Future<List<RuntimeSchedulingStore.DispatchResult>>> futures =
                java.util.stream.IntStream.range(0, 2)
                        .mapToObj(
                                index ->
                                        executor.submit(
                                                () -> {
                                                    ready.countDown();
                                                    ready.await(10, TimeUnit.SECONDS);
                                                    return service.dispatchDue(
                                                            CONTEXT,
                                                            "scheduler-" + index,
                                                            dueAt,
                                                            1,
                                                            Duration.ofMinutes(2),
                                                            TRACE_ID);
                                                }))
                        .toList();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        List<RuntimeSchedulingStore.DispatchResult> left = futures.get(0).get();
        List<RuntimeSchedulingStore.DispatchResult> right = futures.get(1).get();

        assertEquals(1, left.size() + right.size());
        RuntimeSchedulingStore.DispatchResult dispatch =
                left.isEmpty() ? right.getFirst() : left.getFirst();
        assertEquals(END_NODE_ID, dispatch.nodeInstanceId());
        assertEquals(1, dispatch.leaseGeneration());
        assertEquals("DISPATCHED", status("node_instance", END_NODE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.node_instance WHERE id = ? "
                                + "AND current_worker_id = ? AND lease_generation = 1 "
                                + "AND dispatch_count = 1 AND lease_expire_at > transaction_timestamp()",
                        END_NODE_ID,
                        dispatch.dispatchToken()));
    }

    @Test
    @Order(4)
    void retryAndTimerRowsBecomeReadyThenDispatchedWithTaskEvents() {
        admin.update(
                "UPDATE public.node_instance SET next_schedule_at = transaction_timestamp(), "
                        + "updated_at = transaction_timestamp(), updated_by = 'fixture', "
                        + "version = version + 1 WHERE id IN (?, ?)",
                RETRY_NODE_ID,
                TIMER_NODE_ID);
        List<RuntimeSchedulingStore.DispatchResult> dispatched =
                service.dispatchDue(
                        CONTEXT,
                        "scheduler-due",
                        Instant.now().plusSeconds(10),
                        10,
                        Duration.ofMinutes(2),
                        TRACE_ID);

        assertEquals(2, dispatched.size());
        assertEquals("DISPATCHED", status("node_instance", RETRY_NODE_ID));
        assertEquals("DISPATCHED", status("node_instance", TIMER_NODE_ID));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id IN (?, ?) "
                                + "AND event_type = 'workflow.task.ready'",
                        RETRY_NODE_ID,
                        TIMER_NODE_ID));
    }

    @Test
    @Order(5)
    void pausedInstanceCannotDispatchNewWork() {
        admin.update(
                "UPDATE public.node_instance SET next_schedule_at = transaction_timestamp(), "
                        + "updated_at = transaction_timestamp(), updated_by = 'g5-fixture', "
                        + "version = version + 1 WHERE id = ?",
                PAUSED_NODE_ID);
        List<RuntimeSchedulingStore.DispatchResult> result =
                service.dispatchDue(
                        CONTEXT,
                        "paused-scheduler",
                        Instant.now().plusSeconds(30),
                        10,
                        Duration.ofMinutes(2),
                        TRACE_ID);

        assertTrue(result.isEmpty());
        assertEquals("READY", status("node_instance", PAUSED_NODE_ID));
    }

    @Test
    @Order(6)
    void recoversExpiredDispatchAndRunningLeaseWithoutAcceptingTheLateFence() {
        List<RuntimeSchedulingStore.LeaseRecoveryResult> recovered =
                recoveryService.recoverExpired(
                        CONTEXT, "lease-recovery", Instant.now(), 10, TRACE_ID);

        assertEquals(2, recovered.size());
        assertEquals("READY", status("node_instance", RECOVERY_DISPATCHED_NODE_ID));
        assertEquals("READY", status("node_instance", RECOVERY_RUNNING_NODE_ID));
        assertEquals("ABANDONED", status("task_execution", RECOVERY_ATTEMPT_ROW_ID));
        assertEquals("EXPIRED", status("worker_lease", RECOVERY_LEASE_ID));
        assertEquals(
                0,
                admin.update(
                        "UPDATE public.node_instance SET status = 'SUCCEEDED' WHERE id = ? "
                                + "AND status = 'RUNNING' AND current_worker_id = ? "
                                + "AND lease_generation = 1",
                        RECOVERY_RUNNING_NODE_ID,
                        "dispatch:recovery:running"));

        List<RuntimeSchedulingStore.DispatchResult> redispatched =
                service.dispatchDue(
                        CONTEXT,
                        "recovery-redispatch",
                        Instant.now().plusSeconds(10),
                        10,
                        Duration.ofMinutes(2),
                        TRACE_ID);
        assertEquals(2, redispatched.size());
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.node_instance WHERE id IN (?, ?) "
                                + "AND status = 'DISPATCHED' AND dispatch_count = 2 "
                                + "AND attempt_no IN (0, 1) AND lease_generation = 2",
                        RECOVERY_DISPATCHED_NODE_ID,
                        RECOVERY_RUNNING_NODE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.task_execution WHERE id = ? "
                                + "AND status = 'ABANDONED' AND error_code = 'WORKER_LEASE_EXPIRED'",
                        RECOVERY_ATTEMPT_ROW_ID));
    }

    @Test
    @Order(7)
    void anotherTenantCannotDiscoverDueWork() {
        List<RuntimeSchedulingStore.DispatchResult> result =
                service.dispatchDue(
                        new WorkflowRequestContext(TENANT_TWO, "other-scheduler"),
                        "other-scheduler",
                        Instant.now().plusSeconds(30),
                        10,
                        Duration.ofMinutes(2),
                        TRACE_ID);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(8)
    void convergesTheInstanceAfterTheEndNodeSucceeds() {
        admin.update(
                "UPDATE public.node_instance SET status = 'SUCCEEDED', next_schedule_at = NULL, "
                        + "current_worker_id = NULL, lease_expire_at = NULL, output_ref = ?, "
                        + "output_digest = ?, updated_at = transaction_timestamp(), "
                        + "updated_by = 'fixture', version = version + 1 WHERE id = ?",
                "instance-output:" + INSTANCE_ID,
                DIGEST,
                END_NODE_ID);

        RuntimeSchedulingStore.ProgressResult result =
                service.progress(CONTEXT, INSTANCE_ID, TRACE_ID);

        assertEquals(0, result.ready());
        assertEquals("SUCCEEDED", status("workflow_instance", INSTANCE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.runtime.instance.complete.success'",
                        INSTANCE_ID));
    }

    @Test
    @Order(9)
    void materializesFiresAndDeduplicatesPersistentCronOccurrences() {
        List<CronSchedulingStore.CronOccurrence> first =
                cronService.materialize(
                        CONTEXT, CRON_TRIGGER_ID, Instant.parse("2026-08-03T00:03:30Z"), 2, true);
        List<CronSchedulingStore.CronOccurrence> second =
                cronService.materialize(
                        CONTEXT, CRON_TRIGGER_ID, Instant.parse("2026-08-03T00:03:30Z"), 2, true);
        List<CronSchedulingStore.FiredCronOccurrence> fired =
                cronService.fireDue(
                        CONTEXT,
                        "cron-scheduler",
                        Instant.parse("2026-08-03T00:05:00Z"),
                        10,
                        TRACE_ID);

        assertEquals(2, first.size());
        assertEquals(1, second.size());
        assertEquals(3, fired.size());
        assertEquals(
                3,
                count(
                        "SELECT count(*) FROM public.schedule_occurrence WHERE trigger_config_id = ? "
                                + "AND status = 'FIRED' AND workflow_instance_id IS NOT NULL",
                        CRON_TRIGGER_ID));
        assertTrue(
                cronService
                        .fireDue(
                                CONTEXT,
                                "cron-scheduler",
                                Instant.parse("2026-08-03T00:05:00Z"),
                                10,
                                TRACE_ID)
                        .isEmpty());
    }

    @Test
    @Order(10)
    void recordsNoOverlapOccurrencesAsSkipped() {
        List<CronSchedulingStore.CronOccurrence> result =
                cronService.materialize(
                        CONTEXT, CRON_TRIGGER_ID, Instant.parse("2026-08-03T00:06:30Z"), 2, false);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(value -> "SKIPPED".equals(value.status())));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.schedule_occurrence WHERE trigger_config_id = ? "
                                + "AND status = 'SKIPPED' AND claimed_at IS NOT NULL",
                        CRON_TRIGGER_ID));
    }

    @Test
    @Order(11)
    void queriesInstancesNodesAndAttemptsWithStableTenantCursors() {
        admin.update(
                "INSERT INTO public.task_execution (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, attempt_no, execution_id, status, started_at, "
                        + "input_ref, input_digest) VALUES (?, ?, 'query-fixture', 'query-fixture', "
                        + "?, 1, ?, 'RUNNING', transaction_timestamp(), ?, ?)",
                ATTEMPT_ROW_ID,
                TENANT_ONE,
                END_NODE_ID,
                EXECUTION_ID,
                "instance-input:" + INSTANCE_ID,
                DIGEST);

        RuntimeCursorPage<RuntimeQueryStore.InstanceView> first =
                queryService.instances(CONTEXT, null, 2);
        RuntimeCursorPage<RuntimeQueryStore.InstanceView> second =
                queryService.instances(CONTEXT, first.nextCursor(), 2);
        RuntimeCursorPage<RuntimeQueryStore.NodeView> nodes =
                queryService.nodes(CONTEXT, INSTANCE_ID, null, 1);
        RuntimeCursorPage<RuntimeQueryStore.AttemptView> attempts =
                queryService.attempts(CONTEXT, INSTANCE_ID, null, 10);

        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());
        assertTrue(
                second.items().stream()
                        .noneMatch(
                                item ->
                                        first.items().stream()
                                                .anyMatch(
                                                        firstItem ->
                                                                firstItem.id().equals(item.id()))));
        assertEquals(1, nodes.items().size());
        assertTrue(nodes.hasMore());
        assertEquals(1, attempts.items().size());
        assertEquals(EXECUTION_ID, attempts.items().getFirst().executionId());
        assertThrows(
                ApiProblemException.class,
                () ->
                        queryService.instance(
                                new WorkflowRequestContext(TENANT_TWO, "other-query"),
                                INSTANCE_ID));
    }

    private static String status(String table, UUID id) {
        return admin.queryForObject(
                "SELECT status FROM public." + table + " WHERE id = ?", String.class, id);
    }

    private static int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private static void seedFixtures() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g4-scheduler', 'g4-scheduler', 'tenant-one', 'tenant-one', 'ACTIVE'), "
                        + "(?, 'g4-scheduler', 'g4-scheduler', 'tenant-two', 'tenant-two', 'ACTIVE')",
                TENANT_ONE,
                TENANT_TWO);
        admin.update(
                "INSERT INTO public.project (id, tenant_id, created_by, updated_by, name, "
                        + "normalized_name, status) VALUES (?, ?, 'g4-scheduler', 'g4-scheduler', "
                        + "'project-one', 'project-one', 'ACTIVE')",
                PROJECT_ID,
                TENANT_ONE);
        String dsl =
                "{\"schemaVersion\":1,\"name\":\"scheduler-it\",\"nodes\":["
                        + "{\"id\":\"start\",\"type\":\"start\",\"config\":{}},"
                        + "{\"id\":\"end\",\"type\":\"end\",\"config\":{}}],"
                        + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
        admin.update(
                "INSERT INTO public.workflow_definition (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, status, draft_dsl, draft_checksum) "
                        + "VALUES (?, ?, 'g4-scheduler', 'g4-scheduler', ?, 'scheduler-it', "
                        + "'scheduler-it', 'ACTIVE', CAST(? AS jsonb), ?)",
                DEFINITION_ID,
                TENANT_ONE,
                PROJECT_ID,
                dsl,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_version (id, tenant_id, created_by, definition_id, "
                        + "version_no, schema_version, dsl, checksum, executor_catalog_version, "
                        + "published_at, published_by) VALUES (?, ?, 'g4-scheduler', ?, 1, 1, "
                        + "CAST(? AS jsonb), ?, '1', transaction_timestamp(), 'g4-scheduler')",
                VERSION_ID,
                TENANT_ONE,
                DEFINITION_ID,
                dsl,
                DIGEST);
        admin.update(
                "INSERT INTO public.trigger_config (id, tenant_id, created_at, created_by, "
                        + "workflow_version_id, trigger_key, trigger_type, config, timezone, "
                        + "misfire_policy) VALUES (?, ?, CAST(? AS timestamptz), 'g4-scheduler', ?, "
                        + "'cron_minutely', 'cron', CAST(? AS jsonb), 'Asia/Shanghai', "
                        + "'catch_up_limited')",
                CRON_TRIGGER_ID,
                TENANT_ONE,
                "2026-08-03T00:00:00Z",
                VERSION_ID,
                "{\"expression\":\"0 * * * * *\",\"catchUpLimit\":2}");
        insertInstance(INSTANCE_ID);
        insertInstance(DUE_INSTANCE_ID);
        insertPausedInstance();
        insertNode(START_NODE_ID, INSTANCE_ID, "start", "start", "PENDING", null);
        insertNode(END_NODE_ID, INSTANCE_ID, "end", "end", "PENDING", null);
        insertNode(RETRY_NODE_ID, DUE_INSTANCE_ID, "retry", "http", "RETRY_WAIT", "RETRYABLE");
        insertNode(TIMER_NODE_ID, DUE_INSTANCE_ID, "timer", "delay", "WAITING_TIMER", null);
        insertNode(PAUSED_NODE_ID, PAUSED_INSTANCE_ID, "paused", "http", "READY", null);
        insertExpiredRecoveryFixtures();
    }

    private static void insertExpiredRecoveryFixtures() {
        insertExpiredNode(
                RECOVERY_DISPATCHED_NODE_ID,
                "recovery-dispatched",
                "DISPATCHED",
                0,
                "dispatch:recovery:dispatched");
        insertExpiredNode(
                RECOVERY_RUNNING_NODE_ID,
                "recovery-running",
                "RUNNING",
                1,
                "dispatch:recovery:running");
        admin.update(
                "INSERT INTO public.task_execution (id, tenant_id, created_at, created_by, "
                        + "updated_at, updated_by, node_instance_id, attempt_no, execution_id, "
                        + "status, started_at, input_digest) VALUES (?, ?, "
                        + "transaction_timestamp() - interval '150 seconds', 'g5-recovery', "
                        + "transaction_timestamp() - interval '120 seconds', 'g5-recovery', "
                        + "?, 1, ?, 'RUNNING', transaction_timestamp() - interval '120 seconds', ?)",
                RECOVERY_ATTEMPT_ROW_ID,
                TENANT_ONE,
                RECOVERY_RUNNING_NODE_ID,
                RECOVERY_EXECUTION_ID,
                DIGEST);
        admin.update(
                "INSERT INTO public.worker_lease (id, tenant_id, created_at, created_by, "
                        + "updated_at, updated_by, node_instance_id, lease_generation, worker_id, "
                        + "status, acquired_at, expires_at) SELECT ?, n.tenant_id, "
                        + "transaction_timestamp() - interval '150 seconds', 'g5-recovery', "
                        + "transaction_timestamp() - interval '120 seconds', 'g5-recovery', "
                        + "n.id, n.lease_generation, n.current_worker_id, 'ACTIVE', "
                        + "transaction_timestamp() - interval '120 seconds', n.lease_expire_at "
                        + "FROM public.node_instance n WHERE n.id = ?",
                RECOVERY_LEASE_ID,
                RECOVERY_RUNNING_NODE_ID);
    }

    private static void insertExpiredNode(
            UUID id, String nodeId, String nodeStatus, int attemptNo, String workerId) {
        admin.update(
                "INSERT INTO public.node_instance (id, tenant_id, created_at, created_by, "
                        + "updated_at, updated_by, workflow_instance_id, node_id, node_type, status, "
                        + "attempt_no, dispatch_count, lease_generation, current_worker_id, "
                        + "lease_expire_at, config_snapshot) VALUES (?, ?, "
                        + "transaction_timestamp() - interval '180 seconds', 'g5-recovery', "
                        + "transaction_timestamp() - interval '120 seconds', 'g5-recovery', "
                        + "?, ?, 'http', ?, ?, 1, 1, ?, "
                        + "transaction_timestamp() - interval '60 seconds', '{}'::jsonb)",
                id,
                TENANT_ONE,
                DUE_INSTANCE_ID,
                nodeId,
                nodeStatus,
                attemptNo,
                workerId);
    }

    private static void insertPausedInstance() {
        admin.update(
                "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                        + "definition_id, workflow_version_id, status, input_payload, input_digest, started_at, "
                        + "trace_id) VALUES (?, ?, 'g5-scheduler', 'g5-scheduler', ?, ?, "
                        + "'PAUSED', CAST(? AS jsonb), ?, transaction_timestamp(), ?)",
                PAUSED_INSTANCE_ID,
                TENANT_ONE,
                DEFINITION_ID,
                VERSION_ID,
                EMPTY_INPUT,
                EMPTY_INPUT_DIGEST,
                TRACE_ID);
    }

    private static void insertInstance(UUID id) {
        admin.update(
                "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                        + "definition_id, workflow_version_id, status, input_payload, input_digest, "
                        + "started_at, trace_id) "
                        + "VALUES (?, ?, 'g4-scheduler', 'g4-scheduler', ?, ?, "
                        + "CASE WHEN ? = ? THEN 'CREATED' ELSE 'RUNNING' END, CAST(? AS jsonb), ?, "
                        + "CASE WHEN ? = ? THEN NULL ELSE transaction_timestamp() END, ?)",
                id,
                TENANT_ONE,
                DEFINITION_ID,
                VERSION_ID,
                id,
                INSTANCE_ID,
                EMPTY_INPUT,
                EMPTY_INPUT_DIGEST,
                id,
                INSTANCE_ID,
                TRACE_ID);
    }

    private static void insertNode(
            UUID id,
            UUID instanceId,
            String nodeId,
            String nodeType,
            String status,
            String errorCode) {
        admin.update(
                "INSERT INTO public.node_instance (id, tenant_id, created_by, updated_by, "
                        + "workflow_instance_id, node_id, node_type, status, next_schedule_at, error_code) "
                        + "VALUES (?, ?, 'g4-scheduler', 'g4-scheduler', ?, ?, ?, ?, "
                        + "CASE WHEN ? IN ('READY','RETRY_WAIT','WAITING_TIMER') "
                        + "THEN transaction_timestamp() + INTERVAL '1 hour' "
                        + "ELSE NULL END, ?)",
                id,
                TENANT_ONE,
                instanceId,
                nodeId,
                nodeType,
                status,
                status,
                errorCode);
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
