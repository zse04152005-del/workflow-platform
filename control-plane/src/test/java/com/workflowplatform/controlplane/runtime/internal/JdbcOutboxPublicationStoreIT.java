package com.workflowplatform.controlplane.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workflowplatform.controlplane.database.PostgresTestRoleProvisioner;
import com.workflowplatform.controlplane.runtime.OutboxPublicationService;
import com.workflowplatform.controlplane.runtime.OutboxPublicationStore;
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
@SuppressWarnings({"PMD.CloseResource", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class JdbcOutboxPublicationStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g4_outbox";
    private static final String ADMIN_USER = "workflow_g4o_admin";
    private static final String MIGRATION_USER = "workflow_g4o_migration";
    private static final String APPLICATION_USER = "workflow_g4o_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_g4o_platform_admin";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c521");
    private static final UUID TENANT_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c522");
    private static final UUID EVENT_SUCCESS =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c523");
    private static final UUID EVENT_FAILURE =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c524");
    private static final UUID EVENT_CONCURRENT =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c525");
    private static final UUID EVENT_EXPIRED =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c526");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G4-07")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ONE, "g4-outbox");
    private static JdbcTemplate admin;
    private static JdbcOutboxPublicationStore store;
    private static OutboxPublicationService service;

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
        store =
                new JdbcOutboxPublicationStore(
                        new JdbcTemplate(applicationDataSource),
                        new DataSourceTransactionManager(applicationDataSource));
        service = new OutboxPublicationService(store);
        seedFixtures();
    }

    @Test
    @Order(1)
    void successfulSendIsMarkedPublishedAndCannotBeClaimedAgain() {
        List<OutboxPublicationService.PublicationResult> result =
                service.publishBatch(
                        CONTEXT,
                        "publisher-success",
                        Instant.now(),
                        1,
                        Duration.ofMinutes(1),
                        event -> assertEquals(EVENT_SUCCESS, event.eventId()));

        assertEquals(1, result.size());
        assertEquals(
                OutboxPublicationService.PublicationState.PUBLISHED, result.getFirst().state());
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE id = ? "
                                + "AND published_at IS NOT NULL AND publisher_id IS NULL "
                                + "AND publish_attempts = 1 AND version = 2",
                        EVENT_SUCCESS));
    }

    @Test
    @Order(2)
    void failedSendReleasesLeaseAndMovesNextAttemptForward() {
        List<OutboxPublicationService.PublicationResult> result =
                service.publishBatch(
                        CONTEXT,
                        "publisher-failure",
                        Instant.now(),
                        1,
                        Duration.ofMinutes(1),
                        event -> {
                            throw new IllegalStateException("injected broker failure");
                        });

        assertEquals(1, result.size());
        assertEquals(OutboxPublicationService.PublicationState.RETRY, result.getFirst().state());
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.outbox_event WHERE id = ? "
                                + "AND published_at IS NULL AND publisher_id IS NULL "
                                + "AND publish_lease_until IS NULL AND publish_attempts = 1 "
                                + "AND next_publish_at > transaction_timestamp() AND version = 2",
                        EVENT_FAILURE));
        admin.update(
                "UPDATE public.outbox_event SET next_publish_at = transaction_timestamp() "
                        + "+ interval '10 days', version = version + 1 WHERE id = ?",
                EVENT_FAILURE);
    }

    @Test
    @Order(3)
    void concurrentPublishersCannotClaimTheSameEvent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        List<Future<List<OutboxPublicationStore.ClaimedEvent>>> futures =
                java.util.stream.IntStream.range(0, 2)
                        .mapToObj(
                                index ->
                                        executor.submit(
                                                () -> {
                                                    ready.countDown();
                                                    ready.await(10, TimeUnit.SECONDS);
                                                    return store.claim(
                                                            CONTEXT,
                                                            "publisher-concurrent-" + index,
                                                            Instant.now(),
                                                            1,
                                                            Duration.ofMinutes(1));
                                                }))
                        .toList();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        List<OutboxPublicationStore.ClaimedEvent> left = futures.get(0).get();
        List<OutboxPublicationStore.ClaimedEvent> right = futures.get(1).get();

        assertEquals(1, left.size() + right.size());
        OutboxPublicationStore.ClaimedEvent claimed =
                left.isEmpty() ? right.getFirst() : left.getFirst();
        assertEquals(EVENT_CONCURRENT, claimed.eventId());
    }

    @Test
    @Order(4)
    void eventIsResentAfterPublisherCrashesBeforeMarkingSuccess() {
        admin.update(
                "UPDATE public.outbox_event SET publisher_id = 'dead-publisher', "
                        + "publish_lease_until = transaction_timestamp() - interval '1 second', "
                        + "publish_attempts = 1, version = version + 1 WHERE id = ?",
                EVENT_EXPIRED);

        List<OutboxPublicationStore.ClaimedEvent> reclaimed =
                store.claim(
                        CONTEXT,
                        "publisher-recovery",
                        Instant.now().plus(Duration.ofDays(2)),
                        1,
                        Duration.ofMinutes(1));

        assertEquals(1, reclaimed.size());
        assertEquals(EVENT_EXPIRED, reclaimed.getFirst().eventId());
        assertEquals(2, reclaimed.getFirst().publishAttempts());
    }

    @Test
    @Order(5)
    void anotherTenantCannotClaimOutboxRows() {
        assertTrue(
                store.claim(
                                new WorkflowRequestContext(TENANT_TWO, "other-publisher"),
                                "other-publisher",
                                Instant.now().plus(Duration.ofDays(2)),
                                10,
                                Duration.ofMinutes(1))
                        .isEmpty());
    }

    private static int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private static void seedFixtures() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g4-outbox', 'g4-outbox', 'tenant-one', 'tenant-one', 'ACTIVE'), "
                        + "(?, 'g4-outbox', 'g4-outbox', 'tenant-two', 'tenant-two', 'ACTIVE')",
                TENANT_ONE,
                TENANT_TWO);
        insertEvent(EVENT_SUCCESS, "4 minutes", "workflow.task.ready");
        insertEvent(EVENT_FAILURE, "3 minutes", "workflow.events");
        insertEvent(EVENT_CONCURRENT, "2 minutes", "workflow.events");
        insertEvent(EVENT_EXPIRED, "1 minute", "workflow.events");
        admin.update(
                "UPDATE public.outbox_event SET next_publish_at = transaction_timestamp() "
                        + "+ interval '1 day', version = version + 1 WHERE id = ?",
                EVENT_EXPIRED);
    }

    private static void insertEvent(UUID id, String age, String eventType) {
        admin.update(
                "INSERT INTO public.outbox_event (id, tenant_id, created_at, created_by, "
                        + "aggregate_type, aggregate_id, event_type, schema_version, partition_key, "
                        + "payload, payload_digest, occurred_at, next_publish_at) VALUES (?, ?, "
                        + "transaction_timestamp() - CAST(? AS interval), 'g4-outbox', 'node_instance', "
                        + "?, ?, 1, ?, '{\"schemaVersion\":1}'::jsonb, ?, "
                        + "transaction_timestamp() - CAST(? AS interval), "
                        + "transaction_timestamp() - CAST(? AS interval))",
                id,
                TENANT_ONE,
                age,
                id,
                eventType,
                TENANT_ONE + ":" + id,
                "2".repeat(64),
                age,
                age);
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
