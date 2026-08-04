package com.workflowplatform.controlplane.definition;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Secret-free connector or secret-reference metadata used during definition validation. */
public record IntegrationResource(
        UUID id,
        Kind kind,
        String reference,
        UUID tenantId,
        UUID projectId,
        Status status,
        boolean deleted) {

    private static final Pattern REFERENCE =
            Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");

    public IntegrationResource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        if (reference.length() > 200 || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("Invalid integration resource reference");
        }
        if (kind == Kind.CONNECTOR && status == Status.ROTATION_REQUIRED) {
            throw new IllegalArgumentException("Connector status cannot require secret rotation");
        }
    }

    /** Metadata category. */
    public enum Kind {
        CONNECTOR,
        SECRET
    }

    /** Database-backed lifecycle state; only ACTIVE is publishable. */
    public enum Status {
        ACTIVE,
        DISABLED,
        ROTATION_REQUIRED
    }
}
