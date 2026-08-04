package com.workflowplatform.controlplane.runtime.internal;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared transaction-scoped JDBC operations for runtime persistence adapters. */
final class JdbcRuntimeSupport {

    private JdbcRuntimeSupport() {}

    static void setTenantContext(JdbcTemplate jdbc, WorkflowRequestContext context) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            try (PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT public.app_set_tenant_context(?)")) {
                                statement.setObject(1, context.tenantId());
                                statement.execute();
                            }
                            return null;
                        });
    }

    static Instant transactionTimestamp(JdbcTemplate jdbc) {
        OffsetDateTime timestamp =
                jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
        if (timestamp == null) {
            throw new IllegalStateException("transaction timestamp was not returned");
        }
        return timestamp.toInstant();
    }
}
