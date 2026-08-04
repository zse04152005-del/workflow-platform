package com.workflowplatform.worker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.worker.BuiltinWorkerNodeExecutor;
import com.workflowplatform.worker.WorkerExecutionService;
import com.workflowplatform.worker.WorkerTaskStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
class JdbcWorkerTaskStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_worker_g4";
    private static final String ADMIN_USER = "workflow_w_admin";
    private static final String MIGRATION_USER = "workflow_w_migration";
    private static final String APPLICATION_USER = "workflow_w_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_w_platform_admin";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c531");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c532");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c533");
    private static final UUID DEFINITION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c534");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c535");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c536");
    private static final UUID NODE_STORE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c537");
    private static final UUID NODE_SERVICE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c538");
    private static final UUID EVENT_STORE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c539");
    private static final UUID EVENT_SERVICE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c53a");
    private static final UUID NODE_RETRY_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c53b");
    private static final UUID EVENT_RETRY_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c53c");
    private static final UUID NODE_DEAD_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c53d");
    private static final UUID EVENT_DEAD_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c53e");
    private static final String TRACE_ID = "fedcba0987654321fedcba0987654321";
    private static final String DIGEST = "3".repeat(64);
    private static final String STORE_TOKEN = "dispatch:worker-it:store";
    private static final String SERVICE_TOKEN = "dispatch:worker-it:service";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G4-08-G5-08")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static JdbcTemplate admin;
    private static JdbcWorkerTaskStore store;
    private static WorkerTaskStore.ClaimedTask claimedTask;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        provisionRolesAndMigrate();
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_CREDENTIAL));
        DriverManagerDataSource applicationDataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), APPLICATION_USER, APPLICATION_CREDENTIAL);
        objectMapper = new ObjectMapper();
        store =
                new JdbcWorkerTaskStore(
                        new JdbcTemplate(applicationDataSource),
                        new DataSourceTransactionManager(applicationDataSource),
                        objectMapper);
        seedFixtures();
    }

    @Test
    @Order(1)
    void claimCreatesRunningAttemptAndDurableLeaseWithCurrentFence() {
        WorkerTaskStore.ClaimResult result =
                store.claim(event(EVENT_STORE_ID, NODE_STORE_ID), "workflow-worker", "worker-a");
        claimedTask = result.task();

        assertEquals(WorkerTaskStore.ClaimState.CLAIMED, result.state());
        assertEquals(1, claimedTask.attemptNo());
        assertEquals(STORE_TOKEN, claimedTask.dispatchToken());
        assertEquals(1, claimedTask.leaseGeneration());
        assertEquals("RUNNING", nodeStatus(NODE_STORE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.worker_lease WHERE node_instance_id = ? "
                                + "AND status = 'ACTIVE' AND worker_id = ? AND lease_generation = 1",
                        NODE_STORE_ID,
                        STORE_TOKEN));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.task_execution WHERE node_instance_id = ? "
                                + "AND status = 'RUNNING' AND attempt_no = 1",
                        NODE_STORE_ID));
    }

    @Test
    @Order(2)
    void heartbeatAdvancesNodeAndLeaseExpiryTogether() {
        Instant before =
                admin.queryForObject(
                                "SELECT lease_expire_at FROM public.node_instance WHERE id = ?",
                                java.time.OffsetDateTime.class,
                                NODE_STORE_ID)
                        .toInstant();

        store.heartbeat(claimedTask, "worker-a", Duration.ofMinutes(5));

        Instant after =
                admin.queryForObject(
                                "SELECT lease_expire_at FROM public.node_instance WHERE id = ?",
                                java.time.OffsetDateTime.class,
                                NODE_STORE_ID)
                        .toInstant();
        assertTrue(after.isAfter(before));
        assertEquals(
                after,
                admin.queryForObject(
                                "SELECT expires_at FROM public.worker_lease WHERE node_instance_id = ? "
                                        + "AND status = 'ACTIVE'",
                                java.time.OffsetDateTime.class,
                                NODE_STORE_ID)
                        .toInstant());
    }

    @Test
    @Order(3)
    void staleGenerationCannotWriteALateResult() {
        WorkerTaskStore.NodeExecutionResult success =
                WorkerTaskStore.NodeExecutionResult.success(
                        "instance-input:" + INSTANCE_ID, DIGEST);
        assertThrows(
                IllegalStateException.class,
                () -> store.complete(withFence(2, STORE_TOKEN), "worker-a", success));
        assertThrows(
                IllegalStateException.class,
                () -> store.complete(withFence(1, "stale-dispatch-token"), "worker-a", success));
        admin.update(
                "UPDATE public.node_instance SET lease_expire_at = transaction_timestamp() "
                        + "- interval '1 microsecond' WHERE id = ?",
                NODE_STORE_ID);
        assertThrows(
                IllegalStateException.class,
                () -> store.complete(withFence(1, STORE_TOKEN), "worker-a", success));
        admin.update(
                "UPDATE public.node_instance SET lease_expire_at = transaction_timestamp() "
                        + "+ interval '5 minutes' WHERE id = ?",
                NODE_STORE_ID);
        assertEquals("RUNNING", nodeStatus(NODE_STORE_ID));
        assertEquals(
                "RUNNING",
                admin.queryForObject(
                        "SELECT status FROM public.task_execution WHERE execution_id = ?",
                        String.class,
                        claimedTask.executionId()));
    }

    private static WorkerTaskStore.ClaimedTask withFence(long generation, String token) {
        return new WorkerTaskStore.ClaimedTask(
                claimedTask.event(),
                claimedTask.consumerName(),
                claimedTask.workflowInstanceId(),
                claimedTask.executionId(),
                claimedTask.nodeId(),
                claimedTask.nodeType(),
                claimedTask.attemptNo(),
                claimedTask.inputPayload(),
                claimedTask.inputReference(),
                claimedTask.inputDigest(),
                claimedTask.configSnapshot(),
                token,
                generation,
                claimedTask.leaseExpiresAt());
    }

    @Test
    @Order(4)
    void currentFenceCommitsResultAttemptLeaseAuditOutboxAndDedupTogether() {
        store.complete(
                claimedTask,
                "worker-a",
                WorkerTaskStore.NodeExecutionResult.success(
                        "instance-input:" + INSTANCE_ID, DIGEST));

        assertEquals("SUCCEEDED", nodeStatus(NODE_STORE_ID));
        assertEquals(
                "SUCCEEDED",
                admin.queryForObject(
                        "SELECT status FROM public.task_execution WHERE execution_id = ?",
                        String.class,
                        claimedTask.executionId()));
        assertEquals(
                "RELEASED",
                admin.queryForObject(
                        "SELECT status FROM public.worker_lease WHERE node_instance_id = ?",
                        String.class,
                        NODE_STORE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.processed_event WHERE event_id = ?",
                        EVENT_STORE_ID));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.audit_log WHERE resource_id = ?",
                        NODE_STORE_ID.toString()));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ?",
                        NODE_STORE_ID));
        assertEquals(
                WorkerTaskStore.ClaimState.DUPLICATE,
                store.claim(event(EVENT_STORE_ID, NODE_STORE_ID), "workflow-worker", "worker-b")
                        .state());
    }

    @Test
    @Order(5)
    void executionServiceConsumesVersionedEnvelopeAndRunsBuiltinPlugin() throws Exception {
        WorkerExecutionService service =
                new WorkerExecutionService(
                        store,
                        objectMapper,
                        List.of(new BuiltinWorkerNodeExecutor(objectMapper)),
                        "workflow-worker",
                        "worker-service");
        String payload =
                objectMapper.writeValueAsString(
                        Map.of(
                                "eventId",
                                EVENT_SERVICE_ID.toString(),
                                "eventType",
                                "workflow.task.ready",
                                "occurredAt",
                                Instant.now().toString(),
                                "tenantId",
                                TENANT_ONE.toString(),
                                "traceId",
                                TRACE_ID,
                                "schemaVersion",
                                1,
                                "producer",
                                "control_plane",
                                "subject",
                                "node/" + NODE_SERVICE_ID,
                                "payload",
                                Map.of(
                                        "entityType",
                                        "node",
                                        "entityId",
                                        NODE_SERVICE_ID.toString())));

        assertEquals(WorkerExecutionService.ProcessResult.PROCESSED, service.process(payload));
        assertEquals("SUCCEEDED", nodeStatus(NODE_SERVICE_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.processed_event WHERE event_id = ?",
                        EVENT_SERVICE_ID));
    }

    @Test
    @Order(6)
    void retryableFailureUsesFrozenPolicyAndRetryAfterInTheSameTransaction() {
        WorkerTaskStore.ClaimedTask retryTask =
                store.claim(event(EVENT_RETRY_ID, NODE_RETRY_ID), "workflow-worker", "worker-r")
                        .task();

        store.complete(
                retryTask,
                "worker-r",
                WorkerTaskStore.NodeExecutionResult.failure(
                        "HTTP_DOWNSTREAM_ERROR",
                        "managed endpoint unavailable",
                        true,
                        Duration.ofMinutes(2)));

        assertEquals("RETRY_WAIT", nodeStatus(NODE_RETRY_ID));
        Instant nextSchedule =
                admin.queryForObject(
                                "SELECT next_schedule_at FROM public.node_instance WHERE id = ?",
                                java.time.OffsetDateTime.class,
                                NODE_RETRY_ID)
                        .toInstant();
        assertTrue(nextSchedule.isAfter(Instant.now().plusSeconds(100)));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.dead_letter WHERE node_instance_id = ?",
                        NODE_RETRY_ID));
        assertEquals(
                "FAILED",
                admin.queryForObject(
                        "SELECT status FROM public.task_execution WHERE execution_id = ?",
                        String.class,
                        retryTask.executionId()));
    }

    @Test
    @Order(7)
    void terminalFailureCreatesARedactedReferenceOnlyDeadLetter() {
        WorkerTaskStore.ClaimedTask deadTask =
                store.claim(event(EVENT_DEAD_ID, NODE_DEAD_ID), "workflow-worker", "worker-d")
                        .task();

        store.complete(
                deadTask,
                "worker-d",
                WorkerTaskStore.NodeExecutionResult.failure(
                        "NODE_CONFIG_INVALID",
                        "password=do-not-store node configuration is invalid"));

        assertEquals("FAILED", nodeStatus(NODE_DEAD_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.dead_letter WHERE node_instance_id = ? "
                                + "AND status = 'OPEN' "
                                + "AND error_code = 'WORKER_RUNTIME_NODE_CONFIG_INVALID' "
                                + "AND error_summary_redacted = 'password=<redacted> node configuration is invalid' "
                                + "AND payload_ref IS NULL AND payload_digest IS NULL",
                        NODE_DEAD_ID));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.task_execution e JOIN public.dead_letter d "
                                + "ON d.tenant_id = e.tenant_id AND d.task_execution_id = e.id "
                                + "AND d.node_instance_id = e.node_instance_id "
                                + "WHERE e.execution_id = ? AND e.status = 'FAILED'",
                        deadTask.executionId()));
    }

    @Test
    @Order(8)
    void anotherTenantCannotDiscoverTheDispatchedNode() {
        WorkerTaskStore.TaskReadyEvent crossTenant =
                new WorkerTaskStore.TaskReadyEvent(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c53f"),
                        TENANT_TWO,
                        NODE_SERVICE_ID,
                        "workflow.task.ready",
                        1,
                        TRACE_ID);
        assertEquals(
                WorkerTaskStore.ClaimState.STALE,
                store.claim(crossTenant, "workflow-worker", "other-worker").state());
    }

    private static WorkerTaskStore.TaskReadyEvent event(UUID eventId, UUID nodeId) {
        return new WorkerTaskStore.TaskReadyEvent(
                eventId, TENANT_ONE, nodeId, "workflow.task.ready", 1, TRACE_ID);
    }

    private static String nodeStatus(UUID nodeId) {
        return admin.queryForObject(
                "SELECT status FROM public.node_instance WHERE id = ?", String.class, nodeId);
    }

    private static int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private static void provisionRolesAndMigrate() throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(loginRole(MIGRATION_USER, MIGRATION_CREDENTIAL));
            statement.execute(loginRole(APPLICATION_USER, APPLICATION_CREDENTIAL));
            statement.execute("CREATE ROLE " + PLATFORM_ADMIN_ROLE + " NOLOGIN NOBYPASSRLS");
            statement.execute("ALTER DATABASE " + DATABASE + " OWNER TO " + MIGRATION_USER);
            statement.execute("REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + MIGRATION_USER);
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + APPLICATION_USER);
            statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_USER);
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION_USER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APPLICATION_USER);
        }
        java.nio.file.Path repositoryRoot =
                java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        Flyway flyway =
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_CREDENTIAL)
                        .locations(
                                "filesystem:"
                                        + repositoryRoot
                                                .resolve("control-plane")
                                                .resolve("src")
                                                .resolve("main")
                                                .resolve("resources")
                                                .resolve("db")
                                                .resolve("migration")
                                                .toAbsolutePath())
                        .placeholders(
                                Map.of(
                                        "workflowApplicationRole", APPLICATION_USER,
                                        "workflowPlatformAdminRole", PLATFORM_ADMIN_ROLE))
                        .cleanDisabled(true)
                        .load();
        assertEquals(7, flyway.migrate().migrationsExecuted);
    }

    private static void seedFixtures() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'worker-it', 'worker-it', 'tenant-one', 'tenant-one', 'ACTIVE'), "
                        + "(?, 'worker-it', 'worker-it', 'tenant-two', 'tenant-two', 'ACTIVE')",
                TENANT_ONE,
                TENANT_TWO);
        admin.update(
                "INSERT INTO public.project (id, tenant_id, created_by, updated_by, name, "
                        + "normalized_name, status) VALUES (?, ?, 'worker-it', 'worker-it', "
                        + "'project-one', 'project-one', 'ACTIVE')",
                PROJECT_ID,
                TENANT_ONE);
        String dsl =
                "{\"schemaVersion\":1,\"name\":\"worker-it\",\"nodes\":["
                        + "{\"id\":\"store\",\"type\":\"start\",\"config\":{}},"
                        + "{\"id\":\"service\",\"type\":\"start\",\"config\":{}}],"
                        + "\"edges\":[]}";
        admin.update(
                "INSERT INTO public.workflow_definition (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, status, draft_dsl, draft_checksum) "
                        + "VALUES (?, ?, 'worker-it', 'worker-it', ?, 'worker-it', 'worker-it', "
                        + "'ACTIVE', CAST(? AS jsonb), ?)",
                DEFINITION_ID,
                TENANT_ONE,
                PROJECT_ID,
                dsl,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_version (id, tenant_id, created_by, definition_id, "
                        + "version_no, schema_version, dsl, checksum, executor_catalog_version, "
                        + "published_at, published_by) VALUES (?, ?, 'worker-it', ?, 1, 1, "
                        + "CAST(? AS jsonb), ?, '1', transaction_timestamp(), 'worker-it')",
                VERSION_ID,
                TENANT_ONE,
                DEFINITION_ID,
                dsl,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_instance (id, tenant_id, created_by, updated_by, "
                        + "definition_id, workflow_version_id, status, input_digest, input_payload, "
                        + "started_at, trace_id) VALUES (?, ?, 'worker-it', 'worker-it', ?, ?, "
                        + "'RUNNING', ?, '{\"orderId\":\"order-1\"}'::jsonb, "
                        + "transaction_timestamp(), ?)",
                INSTANCE_ID,
                TENANT_ONE,
                DEFINITION_ID,
                VERSION_ID,
                DIGEST,
                TRACE_ID);
        insertDispatchedNode(NODE_STORE_ID, "store", STORE_TOKEN);
        insertDispatchedNode(NODE_SERVICE_ID, "service", SERVICE_TOKEN);
        insertDispatchedNode(
                NODE_RETRY_ID,
                "retry",
                "dispatch:worker-it:retry",
                "{\"_retry\":{\"maxAttempts\":3,\"strategy\":\"fixed\","
                        + "\"initialDelay\":\"PT5S\",\"jitterRatio\":0}}");
        insertDispatchedNode(NODE_DEAD_ID, "dead", "dispatch:worker-it:dead", "{}");
    }

    private static void insertDispatchedNode(UUID nodeId, String definitionNodeId, String token) {
        insertDispatchedNode(nodeId, definitionNodeId, token, "{}");
    }

    private static void insertDispatchedNode(
            UUID nodeId, String definitionNodeId, String token, String configSnapshot) {
        admin.update(
                "INSERT INTO public.node_instance (id, tenant_id, created_by, updated_by, "
                        + "workflow_instance_id, node_id, node_type, status, dispatch_count, "
                        + "lease_generation, current_worker_id, lease_expire_at, config_snapshot) "
                        + "VALUES (?, ?, 'worker-it', 'worker-it', ?, ?, 'start', 'DISPATCHED', "
                        + "1, 1, ?, transaction_timestamp() + interval '10 minutes', "
                        + "CAST(? AS jsonb))",
                nodeId,
                TENANT_ONE,
                INSTANCE_ID,
                definitionNodeId,
                token,
                configSnapshot);
    }

    private static String loginRole(String name, String credential) {
        return "CREATE ROLE "
                + name
                + " LOGIN PASSWORD '"
                + credential
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS";
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
