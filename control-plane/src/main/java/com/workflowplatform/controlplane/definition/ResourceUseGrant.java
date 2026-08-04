package com.workflowplatform.controlplane.definition;

import java.util.Objects;
import java.util.UUID;

/** Explicit permission to use one connector or secret-reference metadata record. */
public record ResourceUseGrant(IntegrationResource.Kind kind, UUID resourceId) {

    public ResourceUseGrant {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resourceId, "resourceId");
    }
}
