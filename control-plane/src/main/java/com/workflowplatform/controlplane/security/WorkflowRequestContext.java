package com.workflowplatform.controlplane.security;

import java.util.Objects;
import java.util.UUID;

/** Trusted identity and tenant context established by the authentication boundary. */
public record WorkflowRequestContext(UUID tenantId, String actorId) {

    public static final String REQUEST_ATTRIBUTE =
            WorkflowRequestContext.class.getName() + ".authenticated";

    public WorkflowRequestContext {
        Objects.requireNonNull(tenantId, "tenantId");
        actorId = Objects.requireNonNull(actorId, "actorId");
        if (actorId.isBlank() || actorId.length() > 255) {
            throw new IllegalArgumentException("actorId must contain 1 to 255 characters");
        }
    }
}
