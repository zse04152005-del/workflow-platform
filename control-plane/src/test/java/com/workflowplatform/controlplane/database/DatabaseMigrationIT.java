package com.workflowplatform.controlplane.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class DatabaseMigrationIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String DATABASE = "workflow_g2_11";
    private static final String ADMIN_USER = "workflow_test_admin";
    private static final String MIGRATION_USER = "workflow_migration";
    private static final String APPLICATION_USER = "workflow_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_platform_admin";
    private static final String FIXTURE_TENANT = "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomHexCredential();
    private static final String MIGRATION_CREDENTIAL = randomHexCredential();
    private static final String APPLICATION_CREDENTIAL = randomHexCredential();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G2-11")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static final List<QueryContract> QUERY_CONTRACTS =
            List.of(
                    new QueryContract(
                            "Q-01",
                            "idx_workflow_instance_tenant_created",
                            "SELECT id, status, created_at FROM public.workflow_instance "
                                    + "WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid "
                                    + "AND (created_at, id) < "
                                    + "('2026-01-01T00:01:40Z'::timestamptz, "
                                    + "'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) "
                                    + "ORDER BY created_at DESC, id DESC LIMIT 50"),
                    new QueryContract(
                            "Q-02",
                            "idx_workflow_instance_business_key",
                            "SELECT id, status FROM public.workflow_instance "
                                    + "WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND business_key = 'business-2500'"),
                    new QueryContract(
                            "Q-03",
                            "idx_node_instance_schedule",
                            "SELECT id FROM public.node_instance WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND status = 'READY' "
                                    + "AND next_schedule_at <= '2026-01-01T01:01:40Z'::timestamptz "
                                    + "ORDER BY next_schedule_at, id LIMIT 50 FOR UPDATE SKIP LOCKED"),
                    new QueryContract(
                            "Q-04",
                            "idx_node_instance_lease_expiry",
                            "SELECT id FROM public.node_instance WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND status = 'RUNNING' "
                                    + "AND lease_expire_at <= '2026-01-01T01:01:40Z'::timestamptz "
                                    + "ORDER BY lease_expire_at, id LIMIT 50"),
                    new QueryContract(
                            "Q-05",
                            "idx_outbox_event_unpublished",
                            "SELECT id FROM public.outbox_event WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND published_at IS NULL "
                                    + "AND next_publish_at <= '2026-01-01T01:01:40Z'::timestamptz "
                                    + "ORDER BY next_publish_at, id LIMIT 50"),
                    new QueryContract(
                            "Q-06",
                            "idx_schedule_occurrence_due",
                            "SELECT id FROM public.schedule_occurrence WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND status = 'PENDING' "
                                    + "AND scheduled_at <= '2026-01-01T00:01:40Z'::timestamptz "
                                    + "ORDER BY scheduled_at, id LIMIT 50"),
                    new QueryContract(
                            "Q-07",
                            "idx_human_task_assignee",
                            "SELECT id FROM public.human_task WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND status = 'WAITING' AND assignee_subject = 'user-41' "
                                    + "ORDER BY created_at, id LIMIT 50"),
                    new QueryContract(
                            "Q-08",
                            "idx_human_task_candidate",
                            "SELECT id FROM public.human_task WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND status = 'WAITING' "
                                    + "AND candidate_role_code = 'finance_reviewer' "
                                    + "ORDER BY created_at, id LIMIT 50"),
                    new QueryContract(
                            "Q-09",
                            "idx_dead_letter_open",
                            "SELECT id FROM public.dead_letter WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND status = 'OPEN' "
                                    + "ORDER BY created_at, id LIMIT 50"),
                    new QueryContract(
                            "Q-10",
                            "idx_audit_log_tenant_time",
                            "SELECT id, occurred_at FROM public.audit_log WHERE tenant_id = '"
                                    + FIXTURE_TENANT
                                    + "'::uuid AND (occurred_at, id) < "
                                    + "('2026-01-01T00:01:40Z'::timestamptz, "
                                    + "'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid) "
                                    + "ORDER BY occurred_at DESC, id DESC LIMIT 50"));

    private static Flyway flyway;
    private static int emptyBusinessTableCount;
    private static int firstMigrationCount;
    private static int repeatMigrationCount;
    private static String currentSchemaVersion;
    private static String postgresVersion;
    private static String flywayVersion;
    private static ValidateResult validationResult;

    @BeforeAll
    static void provisionAndMigrate() throws SQLException, IOException {
        provisionDatabaseRoles();
        try (Connection connection = adminConnection()) {
            emptyBusinessTableCount = businessTableCount(connection);
            postgresVersion = queryString(connection, "SHOW server_version");
        }

        flyway =
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_CREDENTIAL)
                        .locations("classpath:db/migration")
                        .placeholders(
                                Map.of(
                                        "workflowApplicationRole",
                                        APPLICATION_USER,
                                        "workflowPlatformAdminRole",
                                        PLATFORM_ADMIN_ROLE))
                        .cleanDisabled(true)
                        .load();
        flywayVersion = dependencyVersion("org.flywaydb", "flyway-core");
        MigrateResult firstMigration = flyway.migrate();
        firstMigrationCount = firstMigration.migrationsExecuted;
        validationResult = flyway.validateWithResult();
        MigrateResult repeatMigration = flyway.migrate();
        repeatMigrationCount = repeatMigration.migrationsExecuted;
        currentSchemaVersion = flyway.info().current().getVersion().getVersion();
    }

    @Test
    @Order(1)
    void migratesAnEmptyPostgres18DatabaseAndIsRepeatable() {
        assertEquals(0, emptyBusinessTableCount, "the migration must start from an empty schema");
        assertTrue(postgresVersion.startsWith("18.4"), "unexpected PostgreSQL: " + postgresVersion);
        assertEquals("11.7.2", flywayVersion, "unexpected Flyway version");
        assertEquals(7, firstMigrationCount, "fresh migration count");
        assertEquals(0, repeatMigrationCount, "repeat migration must be a no-op");
        assertEquals("7", currentSchemaVersion, "current Flyway schema version");
        assertNotNull(validationResult, "Flyway validation result");
        assertTrue(
                validationResult.validationSuccessful,
                () -> String.valueOf(validationResult.errorDetails));
    }

    @Test
    @Order(2)
    void materializesTheReviewedCatalogAndForcedRls() throws SQLException {
        try (Connection connection = adminConnection()) {
            assertEquals(22, businessTableCount(connection), "business table count");
            assertEquals(
                    330,
                    queryInt(
                            connection,
                            "SELECT count(*) FROM information_schema.columns c "
                                    + "JOIN information_schema.tables t "
                                    + "ON t.table_schema = c.table_schema "
                                    + "AND t.table_name = c.table_name "
                                    + "WHERE c.table_schema = 'public' "
                                    + "AND t.table_type = 'BASE TABLE' "
                                    + "AND c.table_name <> 'flyway_schema_history'"),
                    "business column count");
            assertConstraintCount(connection, "p", 22);
            assertConstraintCount(connection, "f", 39);
            assertConstraintCount(connection, "c", 102);
            int uniqueConstraintCount = constraintCount(connection, "u");
            int standaloneUniqueIndexCount =
                    queryInt(
                            connection,
                            "SELECT count(*) FROM pg_index i "
                                    + "JOIN pg_class idx ON idx.oid = i.indexrelid "
                                    + "JOIN pg_class rel ON rel.oid = i.indrelid "
                                    + "JOIN pg_namespace n ON n.oid = rel.relnamespace "
                                    + "LEFT JOIN pg_constraint c ON c.conindid = i.indexrelid "
                                    + "WHERE n.nspname = 'public' "
                                    + "AND rel.relname <> 'flyway_schema_history' "
                                    + "AND i.indisunique AND c.oid IS NULL");
            assertEquals(41, uniqueConstraintCount, "unique constraint count");
            assertEquals(3, standaloneUniqueIndexCount, "standalone unique index count");
            assertEquals(
                    44,
                    uniqueConstraintCount + standaloneUniqueIndexCount,
                    "total uniqueness guard count");
            assertEquals(
                    47,
                    queryInt(
                            connection,
                            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' "
                                    + "AND indexname LIKE 'idx\\_%' ESCAPE '\\'"),
                    "explicit index count");
            assertEquals(
                    22,
                    queryInt(
                            connection,
                            "SELECT count(*) FROM pg_class c "
                                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                                    + "WHERE n.nspname = 'public' AND c.relkind = 'r' "
                                    + "AND c.relname <> 'flyway_schema_history' "
                                    + "AND c.relrowsecurity AND c.relforcerowsecurity"),
                    "forced RLS table count");
        }
    }

    @Test
    @Order(3)
    void enforcesCheckForeignKeyUniqueRlsAndLifecycleGuards() throws SQLException {
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        try (Connection connection = adminConnection()) {
            insertTenant(connection, firstTenant, "constraint-" + firstTenant, ACTIVE_STATUS);
            insertTenant(connection, secondTenant, "constraint-" + secondTenant, ACTIVE_STATUS);

            expectSqlState(
                    "23514",
                    () ->
                            insertTenant(
                                    connection,
                                    UUID.randomUUID(),
                                    "invalid-status-" + UUID.randomUUID(),
                                    "INVALID"));
            expectSqlState(
                    "23505",
                    () ->
                            insertTenant(
                                    connection,
                                    UUID.randomUUID(),
                                    "constraint-" + firstTenant,
                                    ACTIVE_STATUS));

            UUID foreignProject = UUID.randomUUID();
            insertProject(connection, foreignProject, secondTenant);
            expectSqlState(
                    "23503",
                    () -> insertWorkflowDefinition(connection, firstTenant, foreignProject));

            UUID auditId = insertAuditRow(connection, firstTenant);
            expectSqlState(
                    "55000",
                    () -> {
                        try (PreparedStatement statement =
                                connection.prepareStatement(
                                        "UPDATE public.audit_log SET action = ? WHERE id = ?")) {
                            statement.setString(1, "workflow.changed");
                            statement.setObject(2, auditId);
                            statement.executeUpdate();
                        }
                    });
        }

        assertApplicationRls(firstTenant, secondTenant);
    }

    @Test
    @Order(4)
    void usesEveryReviewedIndexWithTheDefaultPlanner() throws SQLException {
        try (Connection connection = adminConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ClassPathResource("database/g2-11-query-plan-fixture.sql")));
            for (QueryContract contract : QUERY_CONTRACTS) {
                String plan = explain(connection, contract.sql());
                assertTrue(
                        plan.contains("\"Index Name\": \"" + contract.index() + "\""),
                        () -> contract.id() + " did not use " + contract.index() + ":\n" + plan);
                assertFalse(
                        plan.contains("\"Node Type\": \"Seq Scan\""),
                        () -> contract.id() + " regressed to a sequential scan:\n" + plan);
            }
        }
        assertEquals(10, QUERY_CONTRACTS.size(), "reviewed query contract count");
    }

    private static String randomHexCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void provisionDatabaseRoles() throws SQLException {
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

    private static int businessTableCount(Connection connection) throws SQLException {
        return queryInt(
                connection,
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'");
    }

    private static void assertConstraintCount(Connection connection, String type, int expected)
            throws SQLException {
        assertEquals(expected, constraintCount(connection, type), "constraint type " + type);
    }

    private static int constraintCount(Connection connection, String type) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT count(*) FROM pg_constraint c "
                                + "JOIN pg_namespace n ON n.oid = c.connamespace "
                                + "JOIN pg_class rel ON rel.oid = c.conrelid "
                                + "WHERE n.nspname = 'public' "
                                + "AND rel.relname <> 'flyway_schema_history' "
                                + "AND c.contype = ?")) {
            statement.setString(1, type);
            try (ResultSet result = statement.executeQuery()) {
                requireRow(result, "constraint count for " + type);
                return result.getInt(1);
            }
        }
    }

    private static String dependencyVersion(String groupId, String artifactId) throws IOException {
        String resource = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (InputStream input = Flyway.class.getResourceAsStream(resource)) {
            assertNotNull(input, "missing dependency metadata: " + resource);
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version");
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            requireRow(result, "integer query");
            return result.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            requireRow(result, "string query");
            return result.getString(1);
        }
    }

    private static void insertTenant(
            Connection connection, UUID id, String normalizedName, String status)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO public.tenant "
                                + "(id, created_by, updated_by, name, normalized_name, status) "
                                + "VALUES (?, 'g2-11', 'g2-11', ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, normalizedName);
            statement.setString(3, normalizedName);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private static void insertProject(Connection connection, UUID projectId, UUID tenantId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO public.project "
                                + "(id, tenant_id, created_by, updated_by, name, normalized_name, status) "
                                + "VALUES (?, ?, 'g2-11', 'g2-11', 'foreign-project', ?, 'ACTIVE')")) {
            statement.setObject(1, projectId);
            statement.setObject(2, tenantId);
            statement.setString(3, "foreign-" + projectId);
            statement.executeUpdate();
        }
    }

    private static void insertWorkflowDefinition(
            Connection connection, UUID tenantId, UUID foreignProjectId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO public.workflow_definition "
                                + "(id, tenant_id, created_by, updated_by, project_id, name, "
                                + "normalized_name, status, draft_dsl, draft_revision, draft_checksum) "
                                + "VALUES (?, ?, 'g2-11', 'g2-11', ?, 'invalid-definition', ?, "
                                + "'DRAFT', '{}'::jsonb, 0, repeat('a', 64))")) {
            UUID definitionId = UUID.randomUUID();
            statement.setObject(1, definitionId);
            statement.setObject(2, tenantId);
            statement.setObject(3, foreignProjectId);
            statement.setString(4, "invalid-" + definitionId);
            statement.executeUpdate();
        }
    }

    private static UUID insertAuditRow(Connection connection, UUID tenantId) throws SQLException {
        UUID auditId = UUID.randomUUID();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO public.audit_log "
                                + "(id, tenant_id, created_by, actor_type, actor_id, action, "
                                + "resource_type, resource_id, occurred_at, trace_id) "
                                + "VALUES (?, ?, 'g2-11', 'SYSTEM', 'g2-11', "
                                + "'workflow.created', 'workflow', ?, transaction_timestamp(), "
                                + "'4bf92f3577b34da6a3ce929d0e0e4736')")) {
            statement.setObject(1, auditId);
            statement.setObject(2, tenantId);
            statement.setString(3, auditId.toString());
            statement.executeUpdate();
        }
        return auditId;
    }

    private static void assertApplicationRls(UUID visibleTenant, UUID hiddenTenant)
            throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), APPLICATION_USER, APPLICATION_CREDENTIAL)) {
            connection.setAutoCommit(false);
            assertEquals(
                    0,
                    queryInt(connection, "SELECT count(*) FROM public.tenant"),
                    "app role without tenant context must see no tenant");
            try (PreparedStatement context =
                    connection.prepareStatement("SELECT public.app_set_tenant_context(?::uuid)")) {
                context.setString(1, visibleTenant.toString());
                context.execute();
            }
            assertEquals(
                    1,
                    queryInt(connection, "SELECT count(*) FROM public.tenant"),
                    "app role must see only the selected tenant");
            try (PreparedStatement hidden =
                    connection.prepareStatement(
                            "SELECT count(*) FROM public.tenant WHERE id = ?")) {
                hidden.setObject(1, hiddenTenant);
                try (ResultSet result = hidden.executeQuery()) {
                    requireRow(result, "cross-tenant visibility query");
                    assertEquals(0, result.getInt(1), "cross-tenant row must be invisible");
                }
            }
            expectSqlState(
                    "42501",
                    () ->
                            insertTenant(
                                    connection,
                                    UUID.randomUUID(),
                                    "cross-tenant-" + UUID.randomUUID(),
                                    ACTIVE_STATUS));
            connection.rollback();
        }
    }

    private static String explain(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("EXPLAIN (FORMAT JSON, COSTS TRUE) " + sql)) {
            requireRow(result, "EXPLAIN plan");
            return result.getString(1);
        }
    }

    private static void expectSqlState(String expectedState, SqlAction action) {
        SQLException error =
                assertThrows(
                        SQLException.class,
                        action::run,
                        "operation must fail with SQLSTATE " + expectedState);
        assertEquals(expectedState, error.getSQLState(), error::getMessage);
    }

    private static void requireRow(ResultSet result, String context) throws SQLException {
        if (!result.next()) {
            fail("query returned no row: " + context);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private record QueryContract(String id, String index, String sql) {}
}
