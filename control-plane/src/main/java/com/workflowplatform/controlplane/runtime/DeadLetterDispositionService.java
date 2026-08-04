package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.contracts.security.SensitiveTextRedactor;
import com.workflowplatform.controlplane.LengthPrefixedSha256;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.DeadLetterDispositionStore.DeadLetterAction;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Validates, redacts and fingerprints dead-letter operator actions. */
@Service
public final class DeadLetterDispositionService {

    private static final int MAXIMUM_NOTE_LENGTH = 500;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{8,128}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final DeadLetterDispositionStore store;

    public DeadLetterDispositionService(DeadLetterDispositionStore dispositionStore) {
        store = Objects.requireNonNull(dispositionStore, "dispositionStore");
    }

    public DeadLetterDispositionResult addNote(
            WorkflowRequestContext context,
            UUID deadLetterId,
            String note,
            String idempotencyKey,
            String traceparent) {
        return execute(
                context, deadLetterId, DeadLetterAction.NOTE, note, idempotencyKey, traceparent);
    }

    public DeadLetterDispositionResult redispatch(
            WorkflowRequestContext context,
            UUID deadLetterId,
            String reason,
            String idempotencyKey,
            String traceparent) {
        return execute(
                context,
                deadLetterId,
                DeadLetterAction.REDISPATCH,
                reason,
                idempotencyKey,
                traceparent);
    }

    private DeadLetterDispositionResult execute(
            WorkflowRequestContext context,
            UUID deadLetterId,
            DeadLetterAction action,
            String note,
            String idempotencyKey,
            String traceparent) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(deadLetterId, "deadLetterId");
        requireNote(note);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8 to 128 safe characters");
        }
        String normalized = note.trim();
        String operationScope = "runtime:dead-letter:" + action.wireName() + ":" + deadLetterId;
        return store.execute(
                new DeadLetterDispositionStore.DeadLetterDispositionCommand(
                        context,
                        deadLetterId,
                        action,
                        SensitiveTextRedactor.redact(normalized, MAXIMUM_NOTE_LENGTH),
                        traceId(traceparent),
                        TimeOrderedUuidV7.generate(),
                        TimeOrderedUuidV7.generate(),
                        operationScope,
                        LengthPrefixedSha256.digest("idempotency-key-v1", idempotencyKey),
                        LengthPrefixedSha256.digest(
                                "dead-letter-action-v1",
                                deadLetterId.toString(),
                                action.wireName(),
                                normalized)));
    }

    // CPD-OFF: validation mirrors the existing runtime-control boundary by design.
    private static void requireNote(String note) {
        if (note == null || note.isBlank() || note.length() > MAXIMUM_NOTE_LENGTH) {
            throw new IllegalArgumentException("note must contain 1 to 500 characters");
        }
    }

    private static String traceId(String traceparent) {
        if (traceparent == null) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        if (TRACE_ID.matcher(traceparent).matches()
                && !traceparent.chars().allMatch(character -> character == '0')) {
            return traceparent;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (matcher.matches()
                && !matcher.group(1).chars().allMatch(character -> character == '0')) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Traceparent must contain a non-zero W3C trace id");
    }
    // CPD-ON
}
