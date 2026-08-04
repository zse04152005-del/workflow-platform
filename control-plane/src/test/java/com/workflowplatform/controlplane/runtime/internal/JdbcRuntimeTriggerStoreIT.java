package com.workflowplatform.controlplane.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.controlplane.database.PostgresTestRoleProvisioner;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerResult;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerService;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
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
class JdbcRuntimeTriggerStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g4_03";
    private static final String ADMIN_USER = "workflow_g403_admin";
    private static final String MIGRATION_USER = "workflow_g403_migration";
    private static final String APPLICATION_USER = "workflow_g403_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_g403_platform_admin";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c501");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c502");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c503");
    private static final UUID DEFINITION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c504");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c505");
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";
    private static final String DIGEST = "0".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G4-03")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static final WorkflowRequestContext CONTEXT_ONE =
            new WorkflowRequestContext(TENANT_ONE, "g4-03-actor");
    private static final WorkflowRequestContext CONTEXT_TWO =
            new WorkflowRequestContext(TENANT_TWO, "g4-03-other-tenant");
    private static JdbcTemplate admin;
    private static RuntimeTriggerService service;
    private static ObjectMapper objectMapper;

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
        objectMapper = new ObjectMapper();
        service =
                new RuntimeTriggerService(
                        new JdbcRuntimeTriggerStore(
                                application,
                                new DataSourceTransactionManager(applicationDataSource),
                                objectMapper),
                        objectMapper);
        seedFixtures();
    }

    @Test
    @Order(1)
    void persistsImmutableSnapshotNodesAuditOutboxAndReplayAtomically() {
        ObjectNode input = objectMapper.createObjectNode().put("orderId", "order-1001");
        RuntimeTriggerResult first =
                service.manual(
                        CONTEXT_ONE,
                        DEFINITION_ID,
                        input,
                        "order-1001",
                        "g403-happy-path",
                        TRACE_ID);
        RuntimeTriggerResult replay =
                service.manual(
                        CONTEXT_ONE,
                        DEFINITION_ID,
                        input,
                        "order-1001",
                        "g403-happy-path",
                        TRACE_ID);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.instanceId(), replay.instanceId());
        assertEquals(VERSION_ID, first.workflowVersionId());
        assertEquals(1, first.workflowVersion());
        assertEquals("CREATED", first.status());
        assertEquals(202, first.responseStatus());
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.workflow_instance WHERE id = ? "
                                + "AND workflow_version_id = ? AND business_key = 'order-1001' "
                                + "AND trace_id = ? AND input_digest ~ '^[0-9a-f]{64}$'",
                        first.instanceId(),
                        VERSION_ID,
                        TRACE_ID));
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM public.node_instance WHERE workflow_instance_id = ? "
                                + "AND status = 'PENDING'",
                        first.instanceId()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.audit_log WHERE resource_id = ? "
                                + "AND action = 'runtime.instance.trigger.manual'",
                        first.instanceId().toString()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.instance.created'",
                        first.instanceId()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ? "
                                + "AND status = 'COMPLETED' AND response_ref = ?",
                        TENANT_ONE,
                        "/api/v1/instances/" + first.instanceId()));
    }

    @Test
    @Order(2)
    void rejectsIdempotencyKeyReuseWithDifferentRequestWithoutCreatingAnInstance() {
        ObjectNode input = objectMapper.createObjectNode().put("orderId", "order-2001");
        service.http(
                CONTEXT_ONE, DEFINITION_ID, input, "order-2001", "g403-conflict-key", TRACE_ID);
        long before = instanceCount();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.http(
                                CONTEXT_ONE,
                                DEFINITION_ID,
                                objectMapper.createObjectNode().put("orderId", "different-order"),
                                "order-2001",
                                "g403-conflict-key",
                                TRACE_ID));
        assertEquals(before, instanceCount());
    }

    @Test
    @Order(3)
    void concurrentDuplicateTriggerCreatesOneInstanceAndReplaysTheOther() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        List<Future<RuntimeTriggerResult>> futures =
                java.util.stream.IntStream.range(0, 2)
                        .mapToObj(
                                ignored ->
                                        executor.submit(
                                                () -> {
                                                    ready.countDown();
                                                    ready.await(10, TimeUnit.SECONDS);
                                                    return service.manual(
                                                            CONTEXT_ONE,
                                                            DEFINITION_ID,
                                                            objectMapper
                                                                    .createObjectNode()
                                                                    .put(
                                                                            "orderId",
                                                                            "order-concurrent"),
                                                            "order-concurrent",
                                                            "g403-concurrent",
                                                            TRACE_ID);
                                                }))
                        .toList();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        RuntimeTriggerResult left = futures.get(0).get();
        RuntimeTriggerResult right = futures.get(1).get();

        assertEquals(left.instanceId(), right.instanceId());
        assertNotEquals(left.replayed(), right.replayed());
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.workflow_instance WHERE business_key = ?",
                        "order-concurrent"));
    }

    @Test
    @Order(4)
    void outboxFailureRollsBackInstanceNodesAuditAndIdempotency() {
        admin.update("REVOKE INSERT ON TABLE public.outbox_event FROM " + APPLICATION_USER);
        try {
            assertThrows(
                    DataAccessException.class,
                    () ->
                            service.manual(
                                    CONTEXT_ONE,
                                    DEFINITION_ID,
                                    objectMapper.createObjectNode().put("orderId", "rollback"),
                                    "order-rollback",
                                    "g403-rollback",
                                    TRACE_ID));
        } finally {
            admin.update("GRANT INSERT ON TABLE public.outbox_event TO " + APPLICATION_USER);
        }
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.workflow_instance WHERE business_key = ?",
                        "order-rollback"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE created_by = ? "
                                + "AND status = 'PROCESSING'",
                        CONTEXT_ONE.actorId()));
    }

    @Test
    @Order(5)
    void tenantBoundaryDoesNotExposeAnotherTenantPublishedWorkflow() {
        long before = instanceCount();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.manual(
                                CONTEXT_TWO,
                                DEFINITION_ID,
                                objectMapper.createObjectNode().put("orderId", "cross-tenant"),
                                "cross-tenant",
                                "g403-cross-tenant",
                                TRACE_ID));
        assertEquals(before, instanceCount());
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ?",
                        TENANT_TWO));
    }

    private static long instanceCount() {
        return admin.queryForObject("SELECT count(*) FROM public.workflow_instance", Long.class);
    }

    private static int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private static void seedFixtures() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g4-03', 'g4-03', 'tenant-one', 'tenant-one', 'ACTIVE'), "
                        + "(?, 'g4-03', 'g4-03', 'tenant-two', 'tenant-two', 'ACTIVE')",
                TENANT_ONE,
                TENANT_TWO);
        admin.update(
                "INSERT INTO public.project (id, tenant_id, created_by, updated_by, name, "
                        + "normalized_name, status) VALUES (?, ?, 'g4-03', 'g4-03', "
                        + "'project-one', 'project-one', 'ACTIVE')",
                PROJECT_ID,
                TENANT_ONE);
        String dsl =
                "{\"schemaVersion\":1,\"name\":\"trigger-it\",\"nodes\":["
                        + "{\"id\":\"start\",\"type\":\"start\",\"config\":{}},"
                        + "{\"id\":\"end\",\"type\":\"end\",\"config\":{}}],"
                        + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
        admin.update(
                "INSERT INTO public.workflow_definition (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, status, draft_dsl, draft_checksum) "
                        + "VALUES (?, ?, 'g4-03', 'g4-03', ?, 'trigger-it', 'trigger-it', "
                        + "'ACTIVE', CAST(? AS jsonb), ?)",
                DEFINITION_ID,
                TENANT_ONE,
                PROJECT_ID,
                dsl,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_version (id, tenant_id, created_by, definition_id, "
                        + "version_no, schema_version, dsl, checksum, executor_catalog_version, "
                        + "published_at, published_by) VALUES (?, ?, 'g4-03', ?, 1, 1, "
                        + "CAST(? AS jsonb), ?, '1', transaction_timestamp(), 'g4-03')",
                VERSION_ID,
                TENANT_ONE,
                DEFINITION_ID,
                dsl,
                DIGEST);
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
