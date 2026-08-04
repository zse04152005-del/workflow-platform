package com.workflowplatform.controlplane.definition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable tenant/project resource snapshot and caller grants used for publish-time validation.
 */
public record WorkflowConfigurationValidationContext(
        UUID tenantId,
        UUID projectId,
        List<IntegrationResource> resources,
        Set<ResourceUseGrant> useGrants) {

    public WorkflowConfigurationValidationContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        useGrants = Set.copyOf(Objects.requireNonNull(useGrants, "useGrants"));
        requireUniqueResourceScopes(resources);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static void requireUniqueResourceScopes(List<IntegrationResource> resources) {
        Set<ResourceScopeKey> keys = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (IntegrationResource resource : resources) {
            Objects.requireNonNull(resource, "resource");
            if (!ids.add(resource.id())) {
                throw new IllegalArgumentException("Integration resource IDs must be unique");
            }
            ResourceScopeKey key =
                    new ResourceScopeKey(
                            resource.kind(),
                            resource.tenantId(),
                            resource.projectId(),
                            resource.reference());
            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                        "Integration resource references must be unique within a scope");
            }
        }
    }

    private record ResourceScopeKey(
            IntegrationResource.Kind kind, UUID tenantId, UUID projectId, String reference) {}
}
