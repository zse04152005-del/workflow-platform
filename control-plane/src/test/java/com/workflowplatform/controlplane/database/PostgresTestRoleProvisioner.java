package com.workflowplatform.controlplane.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared test-only PostgreSQL role and database privilege provisioner. */
public final class PostgresTestRoleProvisioner {

    private PostgresTestRoleProvisioner() {}

    /** Provisions isolated roles and applies all reviewed migrations to a test container. */
    public static Flyway provisionAndMigrate(
            PostgreSQLContainer<?> postgres,
            String database,
            String migrationUser,
            String migrationCredential,
            String applicationUser,
            String applicationCredential,
            String platformAdminRole)
            throws SQLException {
        Objects.requireNonNull(postgres, "postgres");
        try (Connection connection =
                java.sql.DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            provision(
                    connection,
                    database,
                    migrationUser,
                    migrationCredential,
                    applicationUser,
                    applicationCredential,
                    platformAdminRole);
        }
        Flyway flyway =
                Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), migrationUser, migrationCredential)
                        .locations("classpath:db/migration")
                        .placeholders(
                                Map.of(
                                        "workflowApplicationRole",
                                        applicationUser,
                                        "workflowPlatformAdminRole",
                                        platformAdminRole))
                        .cleanDisabled(true)
                        .load();
        flyway.migrate();
        return flyway;
    }

    /** Creates the migration, application and platform-admin roles used by integration tests. */
    public static void provision(
            Connection connection,
            String database,
            String migrationUser,
            String migrationCredential,
            String applicationUser,
            String applicationCredential,
            String platformAdminRole)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (Statement statement = connection.createStatement()) {
            statement.execute(loginRole(migrationUser, migrationCredential));
            statement.execute(loginRole(applicationUser, applicationCredential));
            statement.execute(
                    "CREATE ROLE "
                            + platformAdminRole
                            + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("ALTER DATABASE " + database + " OWNER TO " + migrationUser);
            statement.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + migrationUser);
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + applicationUser);
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + platformAdminRole);
            statement.execute("ALTER SCHEMA public OWNER TO " + migrationUser);
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + migrationUser);
            statement.execute(
                    "GRANT USAGE ON SCHEMA public TO "
                            + applicationUser
                            + ", "
                            + platformAdminRole);
        }
    }

    private static String loginRole(String name, String credential) {
        return "CREATE ROLE "
                + name
                + " LOGIN PASSWORD '"
                + credential
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS";
    }
}
