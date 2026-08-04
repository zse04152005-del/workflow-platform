package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.UUID;

/** Stable accepted-action result, including first-response idempotent replay metadata. */
public record RuntimeControlResult(
        UUID actionId,
        Instant acceptedAt,
        boolean replayed,
        UUID targetInstanceId,
        String instanceStatus) {}
