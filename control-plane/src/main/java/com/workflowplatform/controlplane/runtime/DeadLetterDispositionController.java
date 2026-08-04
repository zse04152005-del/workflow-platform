package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-isolated manual note and governed redispatch endpoints for dead letters. */
@RestController
@RequestMapping(path = "/api/v1/dead-letters", produces = MediaType.APPLICATION_JSON_VALUE)
public final class DeadLetterDispositionController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String TRACEPARENT_HEADER = "Traceparent";

    private final DeadLetterDispositionService service;

    public DeadLetterDispositionController(DeadLetterDispositionService dispositionService) {
        service = Objects.requireNonNull(dispositionService, "dispositionService");
    }

    @PostMapping(path = "/{deadLetterId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAccepted> addNote(
            WorkflowRequestContext context,
            @PathVariable UUID deadLetterId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(name = TRACEPARENT_HEADER, required = false) String traceparent,
            @Valid @RequestBody AddDeadLetterNoteRequest request) {
        return accepted(
                service.addNote(
                        context, deadLetterId, request.note(), idempotencyKey, traceparent));
    }

    @PostMapping(path = "/{deadLetterId}/redispatch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAccepted> redispatch(
            WorkflowRequestContext context,
            @PathVariable UUID deadLetterId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(name = TRACEPARENT_HEADER, required = false) String traceparent,
            @Valid @RequestBody RedispatchDeadLetterRequest request) {
        return accepted(
                service.redispatch(
                        context, deadLetterId, request.reason(), idempotencyKey, traceparent));
    }

    private static ResponseEntity<ActionAccepted> accepted(DeadLetterDispositionResult result) {
        return ResponseEntity.accepted()
                .body(
                        new ActionAccepted(
                                result.actionId(),
                                "accepted",
                                result.acceptedAt(),
                                result.deadLetterId(),
                                result.deadLetterStatus(),
                                result.targetInstanceId()));
    }

    public record AddDeadLetterNoteRequest(@NotBlank @Size(max = 500) String note) {}

    public record RedispatchDeadLetterRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ActionAccepted(
            UUID actionId,
            String status,
            Instant acceptedAt,
            UUID deadLetterId,
            String deadLetterStatus,
            UUID targetInstanceId) {}
}
