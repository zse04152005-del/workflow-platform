package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.UUID;

/** Stable first-response metadata for a governed dead-letter action. */
public record DeadLetterDispositionResult(
        UUID actionId,
        Instant acceptedAt,
        boolean replayed,
        UUID deadLetterId,
        String deadLetterStatus,
        UUID targetInstanceId) {}
