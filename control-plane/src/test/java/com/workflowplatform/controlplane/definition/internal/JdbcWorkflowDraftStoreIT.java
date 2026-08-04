package com.workflowplatform.controlplane.definition.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.database.PostgresTestRoleProvisioner;
import com.workflowplatform.controlplane.definition.IntegrationResource;
import com.workflowplatform.controlplane.definition.ParsedWorkflowDefinition;
import com.workflowplatform.controlplane.definition.PublishedWorkflow;
import com.workflowplatform.controlplane.definition.ResourceUseGrant;
import com.workflowplatform.controlplane.definition.StrongDraftEtag;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.definition.WorkflowConfigurationValidationContext;
import com.workflowplatform.controlplane.definition.WorkflowDefinitionSourceFormat;
import com.workflowplatform.controlplane.definition.WorkflowDraft;
import com.workflowplatform.controlplane.definition.WorkflowDraftOperationException;
import com.workflowplatform.controlplane.definition.WorkflowDraftService;
import com.workflowplatform.controlplane.definition.WorkflowDraftStore;
import com.workflowplatform.controlplane.definition.WorkflowPortabilityStore;
import com.workflowplatform.controlplane.definition.WorkflowPublicationException;
import com.workflowplatform.controlplane.definition.WorkflowPublicationService;
import com.workflowplatform.controlplane.definition.WorkflowSnapshot;
import com.workflowplatform.controlplane.definition.WorkflowValidationService;
import com.workflowplatform.controlplane.definition.WorkflowValidationTestComponents;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
@SuppressWarnings({
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.AvoidDuplicateLiterals",
    "PMD.CloseResource"
})
class JdbcWorkflowDraftStoreIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g3_06";
    private static final String ADMIN_USER = "workflow_g3_admin";
    private static final String MIGRATION_USER = "workflow_g3_migration";
    private static final String APPLICATION_USER = "workflow_g3_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_g3_platform_admin";
    private static final String TENANT_ONE = "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493";
    private static final String TENANT_TWO = "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494";
    private static final String PROJECT_ONE = "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495";
    private static final String PROJECT_TWO = "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496";
    private static final int G3_10_CONCURRENT_WRITERS = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G3-06")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static final WorkflowRequestContext CONTEXT_ONE =
            new WorkflowRequestContext(UUID.fromString(TENANT_ONE), "g3-06-actor-one");
    private static final WorkflowRequestContext CONTEXT_TWO =
            new WorkflowRequestContext(UUID.fromString(TENANT_TWO), "g3-06-actor-two");
    private static final WorkflowRequestContext REVOKED_CONTEXT =
            new WorkflowRequestContext(UUID.fromString(TENANT_ONE), "g3-07-revoked-actor");
    private static JdbcWorkflowDraftStore store;
    private static JdbcWorkflowValidationContextStore validationContextStore;
    private static WorkflowPublicationService publicationService;
    private static JdbcTemplate adminJdbc;
    private static UUID firstWorkflowId;
    private static WorkflowDraft concurrentDraft;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        provisionRoles();
        Flyway flyway =
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_CREDENTIAL)
                        .locations("classpath:db/migration")
                        .placeholders(
                                java.util.Map.of(
                                        "workflowApplicationRole",
                                        APPLICATION_USER,
                                        "workflowPlatformAdminRole",
                                        PLATFORM_ADMIN_ROLE))
                        .cleanDisabled(true)
                        .load();
        assertEquals(7, flyway.migrate().migrationsExecuted);
        assertEquals("7", flyway.info().current().getVersion().getVersion());
        seedTenantAndProject(TENANT_ONE, PROJECT_ONE, "tenant-one", "project-one");
        seedTenantAndProject(TENANT_TWO, PROJECT_TWO, "tenant-two", "project-two");

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), APPLICATION_USER, APPLICATION_CREDENTIAL);
        JdbcTemplate applicationJdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        store = new JdbcWorkflowDraftStore(applicationJdbc, transactionManager, new ObjectMapper());
        validationContextStore =
                new JdbcWorkflowValidationContextStore(applicationJdbc, transactionManager);
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowDraftService draftService =
                new WorkflowDraftService(
                        WorkflowValidationTestComponents.parser(), store, objectMapper);
        WorkflowValidationService validationService =
                new WorkflowValidationService(
                        draftService,
                        WorkflowValidationTestComponents.parser(),
                        WorkflowValidationTestComponents.graphValidator(),
                        validationContextStore,
                        WorkflowValidationTestComponents.configurationValidator(),
                        WorkflowValidationTestComponents.expressionCompiler(),
                        objectMapper);
        publicationService =
                new WorkflowPublicationService(
                        new JdbcWorkflowPublicationStore(
                                applicationJdbc, transactionManager, store, objectMapper),
                        validationService);
        adminJdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        seedValidationContextFixtures();
    }

    @Test
    @Order(1)
    void createsReadsUpdatesAndReplaysCompletedIdempotentWrites() {
        firstWorkflowId = TimeOrderedUuidV7.generate();
        WorkflowDraftStore.IdempotentRequest createRequest =
                idempotency("workflow:create", '1', '2');
        WorkflowDraft created =
                store.create(
                        createCommand(
                                CONTEXT_ONE,
                                firstWorkflowId,
                                UUID.fromString(PROJECT_ONE),
                                "draft-store-test",
                                "persisted description",
                                "{\"name\":\"draft-store-test\"}",
                                'a',
                                createRequest));

        assertEquals(1, created.draftRevision());
        assertEquals("persisted description", created.description());
        assertEquals(created, store.find(CONTEXT_ONE, firstWorkflowId).orElseThrow());

        WorkflowDraft replayedCreate =
                store.create(
                        createCommand(
                                CONTEXT_ONE,
                                TimeOrderedUuidV7.generate(),
                                UUID.fromString(PROJECT_ONE),
                                "draft-store-test",
                                "persisted description",
                                "{\"name\":\"draft-store-test\"}",
                                'a',
                                createRequest));
        assertEquals(firstWorkflowId, replayedCreate.id());

        WorkflowDraftOperationException duplicateName =
                assertThrows(
                        WorkflowDraftOperationException.class,
                        () ->
                                store.create(
                                        createCommand(
                                                CONTEXT_ONE,
                                                TimeOrderedUuidV7.generate(),
                                                UUID.fromString(PROJECT_ONE),
                                                "draft-store-test",
                                                null,
                                                "{\"name\":\"draft-store-test\"}",
                                                'a',
                                                idempotency("workflow:create", 'a', 'c'))));
        assertEquals(WorkflowDraftOperationException.Failure.CONFLICT, duplicateName.failure());

        WorkflowDraftStore.IdempotentRequest updateRequest =
                idempotency("workflow:draft:update:" + firstWorkflowId, '3', '4');
        WorkflowDraftStore.UpdateCommand update = updateCommand(created, 'b', updateRequest);
        WorkflowDraft updated = store.update(update);
        assertEquals(2, updated.draftRevision());
        assertEquals("b".repeat(64), updated.draftChecksum());
        assertEquals(updated, store.update(update));
    }

    @Test
    @Order(2)
    void seedsExpiredCompletedIdempotencyRecordAndSafelyReusesTheKey() throws SQLException {
        String keyDigest = "e".repeat(64);
        seedExpiredCompletedIdempotencyRecord(keyDigest);

        UUID workflowId = TimeOrderedUuidV7.generate();
        WorkflowDraft created =
                store.create(
                        createCommand(
                                CONTEXT_ONE,
                                workflowId,
                                UUID.fromString(PROJECT_ONE),
                                "expired-key-reuse",
                                null,
                                "{\"name\":\"expired-key-reuse\"}",
                                '9',
                                idempotency("workflow:create", 'e', 'f')));

        assertEquals(workflowId, created.id());
        IdempotencyState state = readIdempotencyState(keyDigest);
        assertEquals("COMPLETED", state.status());
        assertEquals("f".repeat(64), state.requestDigest());
        assertEquals(201, state.responseStatus());
        assertEquals(3, state.version());
        assertTrue(state.futureExpiry());
    }

    @Test
    @Order(3)
    void permitsExactlyOneConcurrentWriterForOneStrongEtag() throws Exception {
        UUID workflowId = TimeOrderedUuidV7.generate();
        concurrentDraft =
                store.create(
                        createCommand(
                                CONTEXT_ONE,
                                workflowId,
                                UUID.fromString(PROJECT_ONE),
                                "concurrent-test",
                                null,
                                "{\"name\":\"concurrent-test\"}",
                                'c',
                                idempotency("workflow:create", '5', '6')));
        WorkflowDraftStore.UpdateCommand left =
                updateCommand(
                        concurrentDraft,
                        'd',
                        idempotency("workflow:draft:update:" + workflowId, '7', '8'));
        WorkflowDraftStore.UpdateCommand right =
                updateCommand(
                        concurrentDraft,
                        'e',
                        idempotency("workflow:draft:update:" + workflowId, '9', '0'));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<WriteResult>> futures =
                    executor.invokeAll(List.of(write(left), write(right)));
            List<WriteResult> results =
                    futures.stream().map(JdbcWorkflowDraftStoreIT::get).toList();
            assertEquals(1, results.stream().filter(WriteResult::success).count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(
                                    result ->
                                            result.failure()
                                                    == WorkflowDraftOperationException.Failure
                                                            .PRECONDITION_FAILED)
                            .count());
            assertEquals(2, store.find(CONTEXT_ONE, workflowId).orElseThrow().draftRevision());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(4)
    void forcedRlsHidesOtherTenantRowsAndKeepsNamesTenantScoped() {
        assertFalse(store.find(CONTEXT_TWO, firstWorkflowId).isPresent());

        WorkflowDraftOperationException hidden =
                assertThrows(
                        WorkflowDraftOperationException.class,
                        () ->
                                store.update(
                                        new WorkflowDraftStore.UpdateCommand(
                                                CONTEXT_TWO,
                                                concurrentDraft.id(),
                                                StrongDraftEtag.parseIfMatch(
                                                        StrongDraftEtag.from(concurrentDraft)),
                                                concurrentDraft.name(),
                                                "{\"name\":\"concurrent-test\"}",
                                                "f".repeat(64),
                                                idempotency(
                                                        "workflow:draft:update:"
                                                                + concurrentDraft.id(),
                                                        'a',
                                                        'b'))));
        assertEquals(WorkflowDraftOperationException.Failure.NOT_FOUND, hidden.failure());

        WorkflowDraft otherTenant =
                store.create(
                        createCommand(
                                CONTEXT_TWO,
                                TimeOrderedUuidV7.generate(),
                                UUID.fromString(PROJECT_TWO),
                                "draft-store-test",
                                null,
                                "{\"name\":\"draft-store-test\"}",
                                '1',
                                idempotency("workflow:create", 'c', 'd')));
        assertTrue(store.find(CONTEXT_TWO, otherTenant.id()).isPresent());
        assertFalse(store.find(CONTEXT_ONE, otherTenant.id()).isPresent());
    }

    @Test
    @Order(5)
    void loadsOnlySecretFreeMetadataAndExpandsActiveScopedPermissionsToExplicitGrants() {
        WorkflowConfigurationValidationContext context =
                validationContextStore.load(CONTEXT_ONE, UUID.fromString(PROJECT_ONE));

        assertEquals(UUID.fromString(TENANT_ONE), context.tenantId());
        assertEquals(UUID.fromString(PROJECT_ONE), context.projectId());
        assertEquals(3, context.resources().size());
        assertTrue(
                context.resources().stream()
                        .allMatch(
                                resource ->
                                        resource.tenantId().equals(UUID.fromString(TENANT_ONE))));
        assertTrue(
                context.resources().stream()
                        .anyMatch(
                                resource ->
                                        resource.kind() == IntegrationResource.Kind.CONNECTOR
                                                && "connectors.primary".equals(resource.reference())
                                                && resource.status()
                                                        == IntegrationResource.Status.ACTIVE));
        assertTrue(
                context.resources().stream()
                        .anyMatch(
                                resource ->
                                        resource.kind() == IntegrationResource.Kind.SECRET
                                                && "secrets.signing".equals(resource.reference())
                                                && resource.projectId() == null));
        assertEquals(3, context.useGrants().size());
        for (IntegrationResource resource : context.resources()) {
            assertTrue(
                    context.useGrants()
                            .contains(new ResourceUseGrant(resource.kind(), resource.id())));
        }
    }

    @Test
    @Order(6)
    void ignoresRevokedBindingsAndLetsForcedRlsHideForeignResources() {
        WorkflowConfigurationValidationContext revoked =
                validationContextStore.load(REVOKED_CONTEXT, UUID.fromString(PROJECT_ONE));
        assertEquals(3, revoked.resources().size());
        assertTrue(revoked.useGrants().isEmpty());

        WorkflowConfigurationValidationContext foreign =
                validationContextStore.load(CONTEXT_TWO, UUID.fromString(PROJECT_TWO));
        assertEquals(1, foreign.resources().size());
        assertEquals("connectors.foreign", foreign.resources().getFirst().reference());
        assertTrue(foreign.useGrants().isEmpty());
        assertTrue(
                foreign.resources().stream()
                        .noneMatch(resource -> "secrets.signing".equals(resource.reference())));
    }

    @Test
    @Order(7)
    void publishesMonotonicImmutableVersionsWithAuditOutboxAndIdempotentReplay() throws Exception {
        UUID workflowId = TimeOrderedUuidV7.generate();
        WorkflowDraft first =
                createValidatedDraft(
                        workflowId,
                        "publication-test",
                        validDefinition("publication-test", 1),
                        '6',
                        '2');

        PublishedWorkflow versionOne =
                publicationService.publish(
                        CONTEXT_ONE,
                        workflowId,
                        first.draftRevision(),
                        "first release",
                        "publish-key-it-001");
        PublishedWorkflow replay =
                publicationService.publish(
                        CONTEXT_ONE,
                        workflowId,
                        first.draftRevision(),
                        "first release",
                        "publish-key-it-001");
        assertEquals(versionOne, replay);
        assertEquals(1, versionOne.version());

        WorkflowDraft second = updateValidatedDraft(first, validDefinition("publication-test", 2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<PublicationResult>> futures =
                    executor.invokeAll(
                            List.of(
                                    publication(
                                            workflowId,
                                            second.draftRevision(),
                                            "second release",
                                            "publish-key-it-002"),
                                    publication(
                                            workflowId,
                                            second.draftRevision(),
                                            "second release",
                                            "publish-key-it-003")));
            List<PublicationResult> results =
                    futures.stream().map(JdbcWorkflowDraftStoreIT::getPublication).toList();
            assertEquals(1, results.stream().filter(PublicationResult::success).count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(
                                    result ->
                                            result.failure()
                                                    == WorkflowPublicationException.Failure
                                                            .CONFLICT)
                            .count());
        } finally {
            executor.shutdownNow();
        }

        List<Integer> versions =
                adminJdbc.queryForList(
                        "SELECT version_no FROM public.workflow_version "
                                + "WHERE definition_id = ? ORDER BY version_no",
                        Integer.class,
                        workflowId);
        assertEquals(List.of(1, 2), versions);
        assertEquals(
                2,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.trigger_config tc "
                                + "JOIN public.workflow_version wv ON wv.id = tc.workflow_version_id "
                                + "WHERE wv.definition_id = ?",
                        Integer.class,
                        workflowId));
        assertEquals(
                2,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.audit_log WHERE "
                                + "after_digest_or_redacted ->> 'workflowId' = ?",
                        Integer.class,
                        workflowId.toString()));
        assertEquals(
                2,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.outbox_event WHERE aggregate_id = ? "
                                + "AND event_type = 'workflow.version.published'",
                        Integer.class,
                        workflowId));
        assertEquals(
                "ACTIVE",
                adminJdbc.queryForObject(
                        "SELECT status FROM public.workflow_definition WHERE id = ?",
                        String.class,
                        workflowId));
        assertNotEquals(versionOne.checksum(), versionsChecksum(workflowId, 2));
    }

    @Test
    @Order(8)
    void rollsBackInvalidAndCrossTenantPublicationAttempts() {
        UUID workflowId = TimeOrderedUuidV7.generate();
        String invalidDefinition =
                validDefinition("invalid-publication", 1)
                        .replace("\"to\":\"end\"", "\"to\":\"missing\"");
        WorkflowDraft invalid =
                createValidatedDraft(
                        workflowId, "invalid-publication", invalidDefinition, '7', '4');

        WorkflowPublicationException validation =
                assertThrows(
                        WorkflowPublicationException.class,
                        () ->
                                publicationService.publish(
                                        CONTEXT_ONE,
                                        workflowId,
                                        invalid.draftRevision(),
                                        null,
                                        "publish-key-it-004"));
        assertEquals(WorkflowPublicationException.Failure.VALIDATION_FAILED, validation.failure());
        assertEquals(0, countVersions(workflowId));

        WorkflowPublicationException hidden =
                assertThrows(
                        WorkflowPublicationException.class,
                        () ->
                                publicationService.publish(
                                        CONTEXT_TWO,
                                        workflowId,
                                        invalid.draftRevision(),
                                        null,
                                        "publish-key-it-005"));
        assertEquals(WorkflowPublicationException.Failure.NOT_FOUND, hidden.failure());
        assertEquals(0, countVersions(workflowId));
        assertEquals(
                0,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.idempotency_record WHERE "
                                + "operation_scope = ?",
                        Integer.class,
                        "workflow:publish:" + workflowId));
    }

    @Test
    @Order(9)
    // G3-09 PostgreSQL evidence for portability snapshot, idempotency and append-only audit.
    void g3_09CreatesAuditedIdempotentCloneAndImportDraftsAndReadsSnapshots() {
        UUID sourceWorkflowId = TimeOrderedUuidV7.generate();
        WorkflowDraft source =
                createValidatedDraft(
                        sourceWorkflowId,
                        "portability-source",
                        validDefinition("portability-source", 1),
                        'b',
                        'e');
        publicationService.publish(
                CONTEXT_ONE,
                sourceWorkflowId,
                source.draftRevision(),
                "portability source",
                "publish-key-it-006");

        WorkflowSnapshot draftSnapshot = store.load(CONTEXT_ONE, sourceWorkflowId, null);
        WorkflowSnapshot versionSnapshot = store.load(CONTEXT_ONE, sourceWorkflowId, 1);
        assertEquals(WorkflowSnapshot.DRAFT, draftSnapshot.sourceKind());
        assertEquals(source.draftChecksum(), draftSnapshot.checksum());
        assertEquals(WorkflowSnapshot.PUBLISHED_VERSION, versionSnapshot.sourceKind());
        assertEquals(1, versionSnapshot.version());

        ParsedWorkflowDefinition cloned = parse(validDefinition("portability-clone", 1));
        WorkflowPortabilityStore.CreateCommand cloneCommand =
                portabilityCommand(
                        source,
                        TimeOrderedUuidV7.generate(),
                        "portability-clone",
                        cloned,
                        "workflow.definition.clone",
                        "workflow:clone:" + sourceWorkflowId,
                        '1',
                        '2');
        WorkflowDraft firstClone = store.create(cloneCommand);
        WorkflowDraft replay = store.create(cloneCommand);
        assertEquals(firstClone, replay);

        ParsedWorkflowDefinition imported = parse(validDefinition("portability-import", 1));
        WorkflowDraft importedDraft =
                store.create(
                        portabilityCommand(
                                source,
                                TimeOrderedUuidV7.generate(),
                                "portability-import",
                                imported,
                                "workflow.definition.import",
                                "workflow:import",
                                '3',
                                '4'));
        assertEquals(1, importedDraft.draftRevision());
        assertEquals("DRAFT", importedDraft.status());
        assertEquals(
                List.of("workflow.definition.clone", "workflow.definition.import"),
                adminJdbc.queryForList(
                        "SELECT action FROM public.audit_log WHERE resource_id IN (?, ?) "
                                + "ORDER BY action",
                        String.class,
                        firstClone.id().toString(),
                        importedDraft.id().toString()));
        assertEquals(
                1,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.audit_log WHERE resource_id = ?",
                        Integer.class,
                        firstClone.id().toString()));
        assertThrows(
                WorkflowDraftOperationException.class,
                () -> store.load(CONTEXT_TWO, sourceWorkflowId, null));
    }

    @Test
    @Order(10)
    void g3_09RollsBackPortabilityIdempotencyAndAuditWhenTargetNameConflicts() {
        UUID sourceWorkflowId = TimeOrderedUuidV7.generate();
        WorkflowDraft source =
                createValidatedDraft(
                        sourceWorkflowId,
                        "portability-conflict-source",
                        validDefinition("portability-conflict-source", 1),
                        'd',
                        '1');
        ParsedWorkflowDefinition duplicate =
                parse(validDefinition("portability-conflict-source", 2));
        String scope = "workflow:import";
        UUID failedTargetId = TimeOrderedUuidV7.generate();

        WorkflowDraftOperationException conflict =
                assertThrows(
                        WorkflowDraftOperationException.class,
                        () ->
                                store.create(
                                        portabilityCommand(
                                                source,
                                                failedTargetId,
                                                "portability-conflict-source",
                                                duplicate,
                                                "workflow.definition.import",
                                                scope,
                                                '5',
                                                '6')));
        assertEquals(WorkflowDraftOperationException.Failure.CONFLICT, conflict.failure());
        assertEquals(
                0,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = ?::uuid "
                                + "AND operation_scope = ? AND key_digest = ?",
                        Integer.class,
                        TENANT_ONE,
                        scope,
                        "5".repeat(64)));
        assertEquals(
                0,
                adminJdbc.queryForObject(
                        "SELECT count(*) FROM public.audit_log WHERE action = "
                                + "'workflow.definition.import' AND resource_id = ?",
                        Integer.class,
                        failedTargetId.toString()));
    }

    @Test
    @Order(11)
    void g3_10AllowsOneOfEightBarrierSynchronizedWritersAndRollsBackLoserClaims() throws Exception {
        UUID workflowId = TimeOrderedUuidV7.generate();
        WorkflowDraft initial =
                store.create(
                        createCommand(
                                CONTEXT_ONE,
                                workflowId,
                                UUID.fromString(PROJECT_ONE),
                                "g3-10-contention",
                                null,
                                "{\"name\":\"g3-10-contention\"}",
                                '7',
                                idempotency("workflow:create", 'a', 'b')));
        String scope = "workflow:draft:update:" + workflowId;
        String keyCharacters = "01234567";
        String requestCharacters = "89abcdef";
        List<WorkflowDraftStore.UpdateCommand> commands =
                IntStream.range(0, G3_10_CONCURRENT_WRITERS)
                        .mapToObj(
                                index ->
                                        updateCommand(
                                                initial,
                                                requestCharacters.charAt(index),
                                                idempotency(
                                                        scope,
                                                        keyCharacters.charAt(index),
                                                        requestCharacters.charAt(index))))
                        .toList();
        CountDownLatch ready = new CountDownLatch(G3_10_CONCURRENT_WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(G3_10_CONCURRENT_WRITERS);
        try {
            List<Future<WriteResult>> futures =
                    commands.stream()
                            .map(
                                    command ->
                                            executor.submit(
                                                    writeAfterBarrier(command, ready, start)))
                            .toList();
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            List<WriteResult> results =
                    futures.stream().map(JdbcWorkflowDraftStoreIT::get).toList();

            assertEquals(1, results.stream().filter(WriteResult::success).count());
            assertEquals(
                    G3_10_CONCURRENT_WRITERS - 1,
                    results.stream()
                            .filter(
                                    result ->
                                            result.failure()
                                                    == WorkflowDraftOperationException.Failure
                                                            .PRECONDITION_FAILED)
                            .count());
            WorkflowDraft current = store.find(CONTEXT_ONE, workflowId).orElseThrow();
            assertEquals(initial.draftRevision() + 1, current.draftRevision());
            assertTrue(current.canonicalDefinition().contains("\"updated\": true"));
            assertEquals(
                    1,
                    adminJdbc.queryForObject(
                            "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = "
                                    + "?::uuid AND operation_scope = ? AND status = 'COMPLETED'",
                            Integer.class,
                            TENANT_ONE,
                            scope));
            assertEquals(
                    0,
                    adminJdbc.queryForObject(
                            "SELECT count(*) FROM public.idempotency_record WHERE tenant_id = "
                                    + "?::uuid AND operation_scope = ? AND status = 'PROCESSING'",
                            Integer.class,
                            TENANT_ONE,
                            scope));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static WorkflowDraft createValidatedDraft(
            UUID workflowId, String name, String definition, char keyDigest, char requestDigest) {
        ParsedWorkflowDefinition parsed = parse(definition);
        return store.create(
                new WorkflowDraftStore.CreateCommand(
                        CONTEXT_ONE,
                        workflowId,
                        UUID.fromString(PROJECT_ONE),
                        name,
                        null,
                        parsed.canonicalJson(),
                        parsed.checksum(),
                        idempotency("workflow:create", keyDigest, requestDigest)));
    }

    private static WorkflowPortabilityStore.CreateCommand portabilityCommand(
            WorkflowDraft source,
            UUID targetWorkflowId,
            String targetName,
            ParsedWorkflowDefinition target,
            String auditAction,
            String scope,
            char keyDigest,
            char requestDigest) {
        return new WorkflowPortabilityStore.CreateCommand(
                CONTEXT_ONE,
                targetWorkflowId,
                UUID.fromString(PROJECT_ONE),
                targetName,
                null,
                target.canonicalJson(),
                target.checksum(),
                auditAction,
                source.id(),
                WorkflowSnapshot.DRAFT,
                source.draftRevision(),
                null,
                source.draftChecksum(),
                "1".repeat(32),
                idempotency(scope, keyDigest, requestDigest));
    }

    private static WorkflowDraft updateValidatedDraft(WorkflowDraft current, String definition) {
        ParsedWorkflowDefinition parsed = parse(definition);
        return store.update(
                new WorkflowDraftStore.UpdateCommand(
                        CONTEXT_ONE,
                        current.id(),
                        StrongDraftEtag.parseIfMatch(StrongDraftEtag.from(current)),
                        current.name(),
                        parsed.canonicalJson(),
                        parsed.checksum(),
                        idempotency("workflow:draft:update:" + current.id(), '8', '9')));
    }

    private static ParsedWorkflowDefinition parse(String definition) {
        return WorkflowValidationTestComponents.parser()
                .parse(
                        definition.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        WorkflowDefinitionSourceFormat.JSON);
    }

    private static String validDefinition(String name, int catalogVersion) {
        return """
                {
                  "schemaVersion":1,
                  "name":"%s",
                  "catalogVersion":%d,
                  "triggers":[{"id":"manual","type":"manual"}],
                  "nodes":[
                    {"id":"start","type":"start","executorVersion":1},
                    {"id":"end","type":"end","executorVersion":1}
                  ],
                  "edges":[{"id":"edge","from":"start","to":"end","on":"success"}]
                }
                """
                .formatted(name, catalogVersion);
    }

    private static Callable<PublicationResult> publication(
            UUID workflowId, long expectedRevision, String releaseNote, String idempotencyKey) {
        return () -> {
            try {
                publicationService.publish(
                        CONTEXT_ONE, workflowId, expectedRevision, releaseNote, idempotencyKey);
                return new PublicationResult(true, null);
            } catch (WorkflowPublicationException exception) {
                return new PublicationResult(false, exception.failure());
            }
        };
    }

    private static PublicationResult getPublication(Future<PublicationResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Concurrent publication test was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Concurrent publication test failed", exception);
        }
    }

    private static int countVersions(UUID workflowId) {
        return adminJdbc.queryForObject(
                "SELECT count(*) FROM public.workflow_version WHERE definition_id = ?",
                Integer.class,
                workflowId);
    }

    private static String versionsChecksum(UUID workflowId, int version) {
        return adminJdbc.queryForObject(
                "SELECT checksum FROM public.workflow_version WHERE definition_id = ? "
                        + "AND version_no = ?",
                String.class,
                workflowId,
                version);
    }

    private static WorkflowDraftStore.CreateCommand createCommand(
            WorkflowRequestContext context,
            UUID workflowId,
            UUID projectId,
            String name,
            String description,
            String definition,
            char checksum,
            WorkflowDraftStore.IdempotentRequest request) {
        return new WorkflowDraftStore.CreateCommand(
                context,
                workflowId,
                projectId,
                name,
                description,
                definition,
                String.valueOf(checksum).repeat(64),
                request);
    }

    private static WorkflowDraftStore.UpdateCommand updateCommand(
            WorkflowDraft current, char checksum, WorkflowDraftStore.IdempotentRequest request) {
        return new WorkflowDraftStore.UpdateCommand(
                CONTEXT_ONE,
                current.id(),
                StrongDraftEtag.parseIfMatch(StrongDraftEtag.from(current)),
                current.name(),
                "{\"name\":\"" + current.name() + "\",\"updated\":true}",
                String.valueOf(checksum).repeat(64),
                request);
    }

    private static WorkflowDraftStore.IdempotentRequest idempotency(
            String scope, char keyDigest, char requestDigest) {
        return new WorkflowDraftStore.IdempotentRequest(
                TimeOrderedUuidV7.generate(),
                scope,
                String.valueOf(keyDigest).repeat(64),
                String.valueOf(requestDigest).repeat(64));
    }

    private static void seedExpiredCompletedIdempotencyRecord(String keyDigest)
            throws SQLException {
        UUID recordId = TimeOrderedUuidV7.generate();
        UUID oldWorkflowId = TimeOrderedUuidV7.generate();
        try (Connection connection = adminConnection();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO public.idempotency_record "
                                        + "(id, tenant_id, created_at, created_by, updated_at, "
                                        + "updated_by, version, operation_scope, key_digest, "
                                        + "request_digest, status, expires_at) VALUES "
                                        + "(?::uuid, ?::uuid, transaction_timestamp() - interval "
                                        + "'48 hours', 'expired-seed', transaction_timestamp() - "
                                        + "interval '48 hours', 'expired-seed', 0, "
                                        + "'workflow:create', ?, ?, 'PROCESSING', "
                                        + "transaction_timestamp() - interval '1 hour')");
                PreparedStatement complete =
                        connection.prepareStatement(
                                "UPDATE public.idempotency_record SET status = 'COMPLETED', "
                                        + "response_status = 201, response_ref = ?, "
                                        + "response_digest = ?, updated_at = transaction_timestamp() "
                                        + "- interval '2 hours', version = version + 1 "
                                        + "WHERE id = ?::uuid")) {
            insert.setString(1, recordId.toString());
            insert.setString(2, TENANT_ONE);
            insert.setString(3, keyDigest);
            insert.setString(4, "a".repeat(64));
            assertEquals(1, insert.executeUpdate());

            complete.setString(1, "/api/v1/workflows/" + oldWorkflowId);
            complete.setString(2, "d".repeat(64));
            complete.setString(3, recordId.toString());
            assertEquals(1, complete.executeUpdate());
        }
    }

    private static IdempotencyState readIdempotencyState(String keyDigest) throws SQLException {
        try (Connection connection = adminConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT status, request_digest, response_status, version, "
                                        + "expires_at > transaction_timestamp() AS future_expiry "
                                        + "FROM public.idempotency_record WHERE tenant_id = ?::uuid "
                                        + "AND operation_scope = 'workflow:create' "
                                        + "AND key_digest = ?")) {
            statement.setString(1, TENANT_ONE);
            statement.setString(2, keyDigest);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("Expired idempotency record was not found");
                }
                return new IdempotencyState(
                        result.getString("status"),
                        result.getString("request_digest"),
                        result.getInt("response_status"),
                        result.getLong("version"),
                        result.getBoolean("future_expiry"));
            }
        }
    }

    private static Callable<WriteResult> write(WorkflowDraftStore.UpdateCommand command) {
        return () -> {
            try {
                store.update(command);
                return new WriteResult(true, null);
            } catch (WorkflowDraftOperationException exception) {
                return new WriteResult(false, exception.failure());
            }
        };
    }

    private static Callable<WriteResult> writeAfterBarrier(
            WorkflowDraftStore.UpdateCommand command, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("G3-10 concurrent draft start barrier timed out");
            }
            return write(command).call();
        };
    }

    private static WriteResult get(Future<WriteResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent draft test was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Concurrent draft test failed", exception);
        }
    }

    private static void provisionRoles() throws SQLException {
        try (Connection connection = adminConnection()) {
            PostgresTestRoleProvisioner.provision(
                    connection,
                    DATABASE,
                    MIGRATION_USER,
                    MIGRATION_CREDENTIAL,
                    APPLICATION_USER,
                    APPLICATION_CREDENTIAL,
                    PLATFORM_ADMIN_ROLE);
        }
    }

    private static void seedTenantAndProject(
            String tenantId, String projectId, String tenantName, String projectName)
            throws SQLException {
        try (Connection connection = adminConnection()) {
            try (PreparedStatement tenant =
                    connection.prepareStatement(
                            "INSERT INTO public.tenant "
                                    + "(id, created_by, updated_by, name, normalized_name, status) "
                                    + "VALUES (?::uuid, 'g3-06', 'g3-06', ?, ?, 'ACTIVE')")) {
                tenant.setString(1, tenantId);
                tenant.setString(2, tenantName);
                tenant.setString(3, tenantName);
                tenant.executeUpdate();
            }
            try (PreparedStatement project =
                    connection.prepareStatement(
                            "INSERT INTO public.project "
                                    + "(id, tenant_id, created_by, updated_by, name, "
                                    + "normalized_name, status) VALUES (?::uuid, ?::uuid, "
                                    + "'g3-06', 'g3-06', ?, ?, 'ACTIVE')")) {
                project.setString(1, projectId);
                project.setString(2, tenantId);
                project.setString(3, projectName);
                project.setString(4, projectName);
                project.executeUpdate();
            }
        }
    }

    private static void seedValidationContextFixtures() {
        DriverManagerDataSource adminDataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate admin = new JdbcTemplate(adminDataSource);
        UUID membership = TimeOrderedUuidV7.generate();
        UUID revokedMembership = TimeOrderedUuidV7.generate();
        UUID role = TimeOrderedUuidV7.generate();
        UUID connector = TimeOrderedUuidV7.generate();
        UUID signingResourceId = TimeOrderedUuidV7.generate();
        UUID disabledResourceId = TimeOrderedUuidV7.generate();

        admin.update(
                "INSERT INTO public.membership (id, tenant_id, created_by, updated_by, "
                        + "principal_subject, scope_type, status) VALUES (?, ?::uuid, 'g3-07', "
                        + "'g3-07', ?, 'TENANT', 'ACTIVE')",
                membership,
                TENANT_ONE,
                CONTEXT_ONE.actorId());
        admin.update(
                "INSERT INTO public.membership (id, tenant_id, created_by, updated_by, "
                        + "principal_subject, scope_type, status) VALUES (?, ?::uuid, 'g3-07', "
                        + "'g3-07', ?, 'TENANT', 'ACTIVE')",
                revokedMembership,
                TENANT_ONE,
                REVOKED_CONTEXT.actorId());
        admin.update(
                "INSERT INTO public.role (id, tenant_id, created_by, updated_by, code, name, "
                        + "permission_codes, status) VALUES (?, ?::uuid, 'g3-07', 'g3-07', "
                        + "'definition_validator', 'Definition validator', "
                        + "ARRAY['connector:use','secret:use'], 'ACTIVE')",
                role,
                TENANT_ONE);
        admin.update(
                "INSERT INTO public.role_binding (id, tenant_id, created_by, updated_by, "
                        + "membership_id, role_id, scope_type, scope_id, status) VALUES "
                        + "(?, ?::uuid, 'g3-07', 'g3-07', ?, ?, 'PROJECT', ?::uuid, 'ACTIVE')",
                TimeOrderedUuidV7.generate(),
                TENANT_ONE,
                membership,
                role,
                PROJECT_ONE);
        admin.update(
                "INSERT INTO public.role_binding (id, tenant_id, created_by, updated_by, "
                        + "revoked_at, revoked_by, revocation_reason, membership_id, role_id, "
                        + "scope_type, scope_id, status) VALUES (?, ?::uuid, 'g3-07', 'g3-07', "
                        + "transaction_timestamp(), 'g3-07', 'test revocation', ?, ?, "
                        + "'PROJECT', ?::uuid, 'REVOKED')",
                TimeOrderedUuidV7.generate(),
                TENANT_ONE,
                revokedMembership,
                role,
                PROJECT_ONE);
        admin.update(
                "INSERT INTO public.connector (id, tenant_id, created_by, updated_by, project_id, "
                        + "name, normalized_name, connector_type, endpoint, config, status) "
                        + "VALUES (?, ?::uuid, 'g3-07', 'g3-07', ?::uuid, 'Primary', "
                        + "'connectors.primary', 'http', 'https://connector.invalid', "
                        + "'{\"private\":\"must-not-load\"}'::jsonb, 'ACTIVE')",
                connector,
                TENANT_ONE,
                PROJECT_ONE);
        admin.update(
                "INSERT INTO public.secret_reference (id, tenant_id, created_by, updated_by, "
                        + "name, normalized_name, provider, ciphertext, key_version, status) "
                        + "VALUES (?, ?::uuid, 'g3-07', 'g3-07', 'Signing', 'secrets.signing', "
                        + "'local', decode('01020304', 'hex'), 'v1', 'ACTIVE')",
                signingResourceId,
                TENANT_ONE);
        admin.update(
                "INSERT INTO public.secret_reference (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, provider, external_key, key_version, "
                        + "status) VALUES (?, ?::uuid, 'g3-07', 'g3-07', ?::uuid, 'Disabled', "
                        + "'secrets.disabled', 'vault', 'forbidden/material/path', 'v1', 'DISABLED')",
                disabledResourceId,
                TENANT_ONE,
                PROJECT_ONE);
        admin.update(
                "INSERT INTO public.connector (id, tenant_id, created_by, updated_by, project_id, "
                        + "name, normalized_name, connector_type, endpoint, config, status) "
                        + "VALUES (?, ?::uuid, 'g3-07', 'g3-07', ?::uuid, 'Foreign', "
                        + "'connectors.foreign', 'http', 'https://foreign.invalid', '{}'::jsonb, "
                        + "'ACTIVE')",
                TimeOrderedUuidV7.generate(),
                TENANT_TWO,
                PROJECT_TWO);
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record IdempotencyState(
            String status,
            String requestDigest,
            int responseStatus,
            long version,
            boolean futureExpiry) {}

    private record WriteResult(boolean success, WorkflowDraftOperationException.Failure failure) {}

    private record PublicationResult(
            boolean success, WorkflowPublicationException.Failure failure) {}
}
