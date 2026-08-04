package com.workflowplatform.controlplane.definition.internal;

import com.workflowplatform.controlplane.definition.IntegrationResource;
import com.workflowplatform.controlplane.definition.ResourceUseGrant;
import com.workflowplatform.controlplane.definition.WorkflowConfigurationValidationContext;
import com.workflowplatform.controlplane.definition.WorkflowValidationContextStore;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC loader that exposes resource metadata and explicit use grants, never secret material. */
@Component
final class JdbcWorkflowValidationContextStore implements WorkflowValidationContextStore {

    private static final String REFERENCE_PATTERN = "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$";
    private static final String RESOURCE_COLUMNS =
            "id, tenant_id, project_id, normalized_name, status, deleted_at";
    private static final String CONNECTOR_QUERY =
            "SELECT DISTINCT ON (project_id, normalized_name) "
                    + RESOURCE_COLUMNS
                    + " FROM public.connector WHERE (project_id IS NULL OR project_id = ?) "
                    + "AND normalized_name ~ ? ORDER BY project_id, normalized_name, "
                    + "(deleted_at IS NULL) DESC, id";
    private static final String SECRET_QUERY =
            "SELECT DISTINCT ON (project_id, normalized_name) "
                    + RESOURCE_COLUMNS
                    + " FROM public.secret_reference WHERE (project_id IS NULL OR project_id = ?) "
                    + "AND normalized_name ~ ? ORDER BY project_id, normalized_name, "
                    + "(deleted_at IS NULL) DESC, id";
    private static final String PERMISSION_QUERY =
            "SELECT EXISTS (SELECT 1 FROM public.membership m "
                    + "JOIN public.role_binding rb ON rb.tenant_id = m.tenant_id "
                    + "AND rb.membership_id = m.id "
                    + "JOIN public.role r ON r.tenant_id = rb.tenant_id AND r.id = rb.role_id "
                    + "WHERE m.principal_subject = ? AND m.status = 'ACTIVE' "
                    + "AND m.revoked_at IS NULL "
                    + "AND (m.scope_type = 'TENANT' "
                    + "OR (m.scope_type = 'PROJECT' AND m.project_id = ?)) "
                    + "AND rb.status = 'ACTIVE' AND rb.revoked_at IS NULL "
                    + "AND r.status = 'ACTIVE' "
                    + "AND (rb.scope_type = 'TENANT' "
                    + "OR (rb.scope_type = 'PROJECT' AND rb.scope_id = ?)) "
                    + "AND ? = ANY(r.permission_codes))";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcWorkflowValidationContextStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public WorkflowConfigurationValidationContext load(
            WorkflowRequestContext context, UUID projectId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(projectId, "projectId");
        WorkflowConfigurationValidationContext result =
                transactions.execute(
                        status -> {
                            setTenantContext(context.tenantId());
                            List<IntegrationResource> resources = loadResources(projectId);
                            Set<ResourceUseGrant> grants =
                                    loadGrants(context.actorId(), projectId, resources);
                            return new WorkflowConfigurationValidationContext(
                                    context.tenantId(), projectId, resources, grants);
                        });
        if (result == null) {
            throw new IllegalStateException("Validation context transaction returned no result");
        }
        return result;
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            try (PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT public.app_set_tenant_context(?)")) {
                                statement.setObject(1, tenantId);
                                statement.execute();
                            }
                            return null;
                        });
    }

    private List<IntegrationResource> loadResources(UUID projectId) {
        List<IntegrationResource> resources = new ArrayList<>();
        resources.addAll(
                jdbc.query(
                        CONNECTOR_QUERY,
                        (result, rowNumber) ->
                                mapResource(result, IntegrationResource.Kind.CONNECTOR),
                        projectId,
                        REFERENCE_PATTERN));
        resources.addAll(
                jdbc.query(
                        SECRET_QUERY,
                        (result, rowNumber) -> mapResource(result, IntegrationResource.Kind.SECRET),
                        projectId,
                        REFERENCE_PATTERN));
        return List.copyOf(resources);
    }

    private Set<ResourceUseGrant> loadGrants(
            String actorId, UUID projectId, List<IntegrationResource> resources) {
        boolean connectorUse = hasPermission(actorId, projectId, "connector:use");
        boolean secretUse = hasPermission(actorId, projectId, "secret:use");
        Set<ResourceUseGrant> grants = new HashSet<>();
        for (IntegrationResource resource : resources) {
            if (resource.kind() == IntegrationResource.Kind.CONNECTOR && connectorUse
                    || resource.kind() == IntegrationResource.Kind.SECRET && secretUse) {
                grants.add(new ResourceUseGrant(resource.kind(), resource.id()));
            }
        }
        return Set.copyOf(grants);
    }

    private boolean hasPermission(String actorId, UUID projectId, String permission) {
        Boolean result =
                jdbc.queryForObject(
                        PERMISSION_QUERY, Boolean.class, actorId, projectId, projectId, permission);
        return Boolean.TRUE.equals(result);
    }

    private static IntegrationResource mapResource(ResultSet result, IntegrationResource.Kind kind)
            throws SQLException {
        return new IntegrationResource(
                result.getObject("id", UUID.class),
                kind,
                result.getString("normalized_name"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("project_id", UUID.class),
                IntegrationResource.Status.valueOf(result.getString("status")),
                result.getTimestamp("deleted_at") != null);
    }
}
