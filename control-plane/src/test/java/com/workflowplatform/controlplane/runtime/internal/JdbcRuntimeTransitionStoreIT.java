package com.workflowplatform.controlplane.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.database.PostgresTestRoleProvisioner;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.RuntimeEntityType;
import com.workflowplatform.controlplane.runtime.RuntimeStateTransitionService;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionActor;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionCommand;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionEffects;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataAccessException;
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
class JdbcRuntimeTransitionStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g4_02";
    private static final String ADMIN_USER = "workflow_g4_admin";
    private static final String MIGRATION_USER = "workflow_g4_migration";
    private static final String APPLICATION_USER = "workflow_g4_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_g4_platform_admin";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final UUID DEFINITION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c497");
    private static final UUID TRIGGER_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c498");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c499");
    private static final UUID ROLLBACK_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c49a");
    private static final UUID CONCURRENT_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c49b");
    private static final UUID NODE_READY_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c49c");
    private static final UUID NODE_RUNNING_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c49d");
    private static final UUID NODE_DISPATCHED_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c49e");
    private static final UUID ATTEMPT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c49f");
    private static final UUID SCHEDULE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c4a0");
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String DIGEST = HexFormat.of().formatHex(new byte[32]);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G4-02")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static JdbcTemplate admin;
    private static JdbcTemplate application;
    private static RuntimeStateTransitionService service;
    private static WorkflowRequestContext context;

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
        application =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), APPLICATION_USER, APPLICATION_CREDENTIAL));
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(application.getDataSource());
        service =
                new RuntimeStateTransitionService(
                        new JdbcRuntimeTransitionStore(
                                application, transactions, new ObjectMapper()));
        context = new WorkflowRequestContext(TENANT_ONE, "g4-02-runtime");
        seedFixtures();
    }

    @Test
    @Order(1)
    void commitsStateAuditAndOutboxTogetherForInstance() {
        var result =
                service.transition(
                        command(
                                RuntimeEntityType.INSTANCE,
                                INSTANCE_ID,
                                "CREATED",
                                "RUNNING",
                                "start",
                                RuntimeTransitionActor.RUNTIME,
                                RuntimeTransitionEffects.none()));
        assertEquals(1, result.version());
        assertEquals(
                "RUNNING",
                admin.queryForObject(
                        "SELECT status FROM public.workflow_instance WHERE id = ?",
                        String.class,
                        INSTANCE_ID));
        assertEquals(1, count("audit_log", INSTANCE_ID));
        assertEquals(1, count("outbox_event", INSTANCE_ID));
    }

    @Test
    @Order(2)
    void staleExpectedVersionIsAStableVersionConflict() {
        RuntimeTransitionException exception =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                service.transition(
                                        command(
                                                RuntimeEntityType.INSTANCE,
                                                INSTANCE_ID,
                                                "CREATED",
                                                "RUNNING",
                                                "start",
                                                RuntimeTransitionActor.RUNTIME,
                                                RuntimeTransitionEffects.none())));
        assertEquals(RuntimeTransitionException.Failure.VERSION_CONFLICT, exception.failure());
    }

    @Test
    @Order(3)
    void supportsNodeAttemptAndScheduleEntities() {
        Instant scheduled = Instant.now().plusSeconds(60);
        service.transition(
                command(
                        RuntimeEntityType.NODE,
                        NODE_READY_ID,
                        "PENDING",
                        "READY",
                        "make_ready",
                        RuntimeTransitionActor.RUNTIME,
                        effects(scheduled, null, null, null, null, null, null, null, null)));
        service.transition(
                command(
                        RuntimeEntityType.ATTEMPT,
                        ATTEMPT_ID,
                        "RUNNING",
                        "TIMED_OUT",
                        "timeout",
                        RuntimeTransitionActor.SCHEDULER,
                        RuntimeTransitionEffects.noneWithError(
                                "EXECUTION_DEADLINE", "deadline reached")));
        service.transition(
                command(
                        RuntimeEntityType.SCHEDULE,
                        SCHEDULE_ID,
                        "PENDING",
                        "CLAIMED",
                        "claim_occurrence",
                        RuntimeTransitionActor.SCHEDULER,
                        RuntimeTransitionEffects.none()));
        assertEquals(
                "READY",
                admin.queryForObject(
                        "SELECT status FROM public.node_instance WHERE id = ?",
                        String.class,
                        NODE_READY_ID));
        assertEquals(
                "TIMED_OUT",
                admin.queryForObject(
                        "SELECT status FROM public.task_execution WHERE id = ?",
                        String.class,
                        ATTEMPT_ID));
        assertEquals(
                "CLAIMED",
                admin.queryForObject(
                        "SELECT status FROM public.schedule_occurrence WHERE id = ?",
                        String.class,
                        SCHEDULE_ID));
    }

    @Test
    @Order(4)
    void fencingTokenIsCheckedForWorkerClaim() {
        RuntimeTransitionEffects effects =
                new RuntimeTransitionEffects(
                        null, "worker-a", null, 99L, null, null, null, null, null);
        RuntimeTransitionException exception =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                service.transition(
                                        command(
                                                RuntimeEntityType.NODE,
                                                NODE_DISPATCHED_ID,
                                                "DISPATCHED",
                                                "RUNNING",
                                                "claim",
                                                RuntimeTransitionActor.WORKER,
                                                effects)));
        assertEquals(
                RuntimeTransitionException.Failure.FENCING_TOKEN_CONFLICT, exception.failure());
    }

    @Test
    @Order(5)
    void workerClaimIncrementsOnlyTheAttemptCounter() {
        RuntimeTransitionEffects effects =
                new RuntimeTransitionEffects(
                        null, "worker-a", null, 1L, null, null, null, null, null);
        service.transition(
                command(
                        RuntimeEntityType.NODE,
                        NODE_DISPATCHED_ID,
                        "DISPATCHED",
                        "RUNNING",
                        "claim",
                        RuntimeTransitionActor.WORKER,
                        effects));

        assertEquals(
                1,
                admin.queryForObject(
                        "SELECT attempt_no FROM public.node_instance WHERE id = ?",
                        Integer.class,
                        NODE_DISPATCHED_ID));
        assertEquals(
                0,
                admin.queryForObject(
                        "SELECT dispatch_count FROM public.node_instance WHERE id = ?",
                        Integer.class,
                        NODE_DISPATCHED_ID));
        assertEquals(
                1L,
                admin.queryForObject(
                        "SELECT lease_generation FROM public.node_instance WHERE id = ?",
                        Long.class,
                        NODE_DISPATCHED_ID));
    }

    @Test
    @Order(6)
    void missingOutboxPermissionRollsBackStateAndAudit() {
        admin.update("REVOKE INSERT ON TABLE public.outbox_event FROM " + APPLICATION_USER);
        try {
            assertThrows(
                    DataAccessException.class,
                    () ->
                            service.transition(
                                    command(
                                            RuntimeEntityType.INSTANCE,
                                            ROLLBACK_INSTANCE_ID,
                                            "CREATED",
                                            "RUNNING",
                                            "start",
                                            RuntimeTransitionActor.RUNTIME,
                                            RuntimeTransitionEffects.none())));
        } finally {
            admin.update("GRANT INSERT ON TABLE public.outbox_event TO " + APPLICATION_USER);
        }
        assertEquals(
                "CREATED",
                admin.queryForObject(
                        "SELECT status FROM public.workflow_instance WHERE id = ?",
                        String.class,
                        ROLLBACK_INSTANCE_ID));
        assertEquals(0, count("audit_log", ROLLBACK_INSTANCE_ID));
        assertEquals(0, count("outbox_event", ROLLBACK_INSTANCE_ID));
    }

    @Test
    @Order(7)
    void concurrentWritersPermitExactlyOneVersionZeroTransition() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        List<Future<Object>> futures =
                IntStream.range(0, 2)
                        .mapToObj(
                                ignored ->
                                        executor.submit(
                                                () -> {
                                                    ready.countDown();
                                                    ready.await(10, TimeUnit.SECONDS);
                                                    try {
                                                        return service.transition(
                                                                command(
                                                                        RuntimeEntityType.INSTANCE,
                                                                        CONCURRENT_INSTANCE_ID,
                                                                        "CREATED",
                                                                        "RUNNING",
                                                                        "start",
                                                                        RuntimeTransitionActor
                                                                                .RUNTIME,
                                                                        RuntimeTransitionEffects
                                                                                .none()));
                                                    } catch (RuntimeTransitionException exception) {
                                                        return exception.failure();
                                                    }
                                                }))
                        .toList();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        long successes = 0;
        long conflicts = 0;
        for (Future<Object> future : futures) {
            Object result = future.get();
            if (result instanceof RuntimeTransitionException.Failure failure
                    && failure == RuntimeTransitionException.Failure.VERSION_CONFLICT) {
                conflicts++;
            } else {
                successes++;
            }
        }
        assertEquals(1, successes);
        assertEquals(1, conflicts);
        assertEquals(
                1L,
                admin.queryForObject(
                        "SELECT version FROM public.workflow_instance WHERE id = ?",
                        Long.class,
                        CONCURRENT_INSTANCE_ID));
    }

    @Test
    @Order(8)
    void anotherTenantCannotDiscoverOrMutateTheRow() {
        RuntimeTransitionException exception =
                assertThrows(
                        RuntimeTransitionException.class,
                        () ->
                                new RuntimeStateTransitionService(
                                                new JdbcRuntimeTransitionStore(
                                                        application,
                                                        new DataSourceTransactionManager(
                                                                application.getDataSource()),
                                                        new ObjectMapper()))
                                        .transition(
                                                new RuntimeTransitionCommand(
                                                        new WorkflowRequestContext(
                                                                TENANT_TWO, "other-tenant"),
                                                        RuntimeEntityType.INSTANCE,
                                                        INSTANCE_ID,
                                                        "start",
                                                        "CREATED",
                                                        0,
                                                        "RUNNING",
                                                        RuntimeTransitionActor.RUNTIME,
                                                        "TENANT_BOUNDARY",
                                                        TRACE_ID,
                                                        RuntimeTransitionEffects.none())));
        assertEquals(RuntimeTransitionException.Failure.NOT_FOUND, exception.failure());
    }

    private static RuntimeTransitionCommand command(
            RuntimeEntityType entity,
            UUID id,
            String from,
            String to,
            String operation,
            RuntimeTransitionActor actor,
            RuntimeTransitionEffects effects) {
        return new RuntimeTransitionCommand(
                context,
                entity,
                id,
                operation,
                from,
                0,
                to,
                actor,
                "TEST_TRANSITION",
                TRACE_ID,
                effects);
    }

    private static RuntimeTransitionEffects effects(
            Instant nextScheduleAt,
            String workerId,
            Instant leaseExpiresAt,
            Long expectedLeaseGeneration,
            UUID workflowInstanceId,
            String outputReference,
            String outputDigest,
            String errorCode,
            String errorSummary) {
        return new RuntimeTransitionEffects(
                nextScheduleAt,
                workerId,
                leaseExpiresAt,
                expectedLeaseGeneration,
                workflowInstanceId,
                outputReference,
                outputDigest,
                errorCode,
                errorSummary);
    }

    private static int count(String table, UUID aggregateId) {
        return admin.queryForObject(
                "SELECT count(*) FROM public."
                        + table
                        + " WHERE "
                        + ("audit_log".equals(table) ? "resource_id = ?" : "aggregate_id = ?"),
                Integer.class,
                "audit_log".equals(table) ? aggregateId.toString() : aggregateId);
    }

    private static void seedFixtures() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g4-02', 'g4-02', 'tenant-one', 'tenant-one', 'ACTIVE')",
                TENANT_ONE);
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g4-02', 'g4-02', 'tenant-two', 'tenant-two', 'ACTIVE')",
                TENANT_TWO);
        admin.update(
                "INSERT INTO public.project (id, tenant_id, created_by, updated_by, name, "
                        + "normalized_name, status) VALUES (?, ?, 'g4-02', 'g4-02', "
                        + "'project-one', 'project-one', 'ACTIVE')",
                PROJECT_ID,
                TENANT_ONE);
        admin.update(
                "INSERT INTO public.workflow_definition (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, status, draft_dsl, draft_checksum) "
                        + "VALUES (?, ?, 'g4-02', 'g4-02', ?, 'runtime-test', 'runtime-test', "
                        + "'ACTIVE', '{}'::jsonb, ?)",
                DEFINITION_ID,
                TENANT_ONE,
                PROJECT_ID,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_version (id, tenant_id, created_by, definition_id, "
                        + "version_no, schema_version, dsl, checksum, executor_catalog_version, "
                        + "published_at, published_by) VALUES (?, ?, 'g4-02', ?, 1, 1, '{}'::jsonb, "
                        + "?, '1', transaction_timestamp(), 'g4-02')",
                VERSION_ID,
                TENANT_ONE,
                DEFINITION_ID,
                DIGEST);
        admin.update(
                "INSERT INTO public.trigger_config (id, tenant_id, created_by, workflow_version_id, "
                        + "trigger_key, trigger_type, config, timezone, misfire_policy) VALUES (?, ?, "
                        + "'g4-02', ?, 'cron', 'cron', '{\"cron\":\"* * * * *\"}'::jsonb, 'UTC', 'skip')",
                TRIGGER_ID,
                TENANT_ONE,
                VERSION_ID);
        insertInstance(INSTANCE_ID);
        insertInstance(ROLLBACK_INSTANCE_ID);
        insertInstance(CONCURRENT_INSTANCE_ID);
        insertNode(NODE_READY_ID, INSTANCE_ID, "PENDING", null, null, 0);
        insertNode(NODE_RUNNING_ID, INSTANCE_ID, "RUNNING", "worker-a", 1L, 1);
        insertNode(NODE_DISPATCHED_ID, INSTANCE_ID, "DISPATCHED", "worker-a", 1L, 0);
        admin.update(
                "INSERT INTO public.task_execution (id, tenant_id, created_by, updated_by, "
                        + "node_instance_id, attempt_no, execution_id, status, started_at, input_digest) "
                        + "VALUES (?, ?, 'g4-02', 'g4-02', ?, 1, ?, 'RUNNING', "
                        + "transaction_timestamp(), ?)",
                ATTEMPT_ID,
                TENANT_ONE,
                NODE_RUNNING_ID,
                TimeOrderedUuidV7.generate(),
                DIGEST);
        admin.update(
                "INSERT INTO public.schedule_occurrence (id, tenant_id, created_by, updated_by, "
                        + "trigger_config_id, scheduled_at, timezone, misfire_policy, status) VALUES "
                        + "(?, ?, 'g4-02', 'g4-02', ?, transaction_timestamp(), 'UTC', 'skip', 'PENDING')",
                SCHEDULE_ID,
                TENANT_ONE,
                TRIGGER_ID);
    }

    private static void insertInstance(UUID id) {
        admin.update(
                "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                        + "definition_id, workflow_version_id, status, input_digest, trace_id) "
                        + "VALUES (?, ?, 'g4-02', 'g4-02', ?, ?, 'CREATED', ?, ?)",
                id,
                TENANT_ONE,
                DEFINITION_ID,
                VERSION_ID,
                DIGEST,
                TRACE_ID);
    }

    private static void insertNode(
            UUID id,
            UUID instanceId,
            String status,
            String workerId,
            Long generation,
            int attemptNo) {
        admin.update(
                "INSERT INTO public.node_instance (id, tenant_id, created_by, updated_by, "
                        + "workflow_instance_id, node_id, node_type, status, attempt_no, "
                        + "lease_generation, current_worker_id, lease_expire_at) VALUES (?, ?, "
                        + "'g4-02', 'g4-02', ?, ?, 'http', ?, ?, ?, ?, CASE WHEN ?::bigint IS NULL "
                        + "THEN NULL ELSE transaction_timestamp() + interval '5 minutes' END)",
                id,
                TENANT_ONE,
                instanceId,
                "node-" + id.toString().substring(30),
                status,
                attemptNo,
                generation == null ? 0 : generation,
                workerId,
                generation);
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
