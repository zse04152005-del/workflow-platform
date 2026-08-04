package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlAction;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

/** Tenant-isolated pause, resume, cooperative cancel and replay endpoints. */
@RestController
@RequestMapping(path = "/api/v1/instances", produces = MediaType.APPLICATION_JSON_VALUE)
public final class RuntimeControlController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String TRACEPARENT_HEADER = "Traceparent";

    private final RuntimeControlService service;

    public RuntimeControlController(RuntimeControlService controlService) {
        service = Objects.requireNonNull(controlService, "controlService");
    }

    @PostMapping(path = "/{instanceId}/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAccepted> pause(
            WorkflowRequestContext context,
            @PathVariable UUID instanceId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(name = TRACEPARENT_HEADER, required = false) String traceparent,
            @Valid @RequestBody ControlInstanceRequest request) {
        return accepted(
                service.control(
                        context,
                        instanceId,
                        RuntimeControlAction.PAUSE,
                        request.reason(),
                        null,
                        idempotencyKey,
                        traceparent));
    }

    @PostMapping(path = "/{instanceId}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAccepted> resume(
            WorkflowRequestContext context,
            @PathVariable UUID instanceId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(name = TRACEPARENT_HEADER, required = false) String traceparent,
            @Valid @RequestBody ControlInstanceRequest request) {
        return accepted(
                service.control(
                        context,
                        instanceId,
                        RuntimeControlAction.RESUME,
                        request.reason(),
                        null,
                        idempotencyKey,
                        traceparent));
    }

    @PostMapping(path = "/{instanceId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAccepted> cancel(
            WorkflowRequestContext context,
            @PathVariable UUID instanceId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(name = TRACEPARENT_HEADER, required = false) String traceparent,
            @Valid @RequestBody ControlInstanceRequest request) {
        return accepted(
                service.control(
                        context,
                        instanceId,
                        RuntimeControlAction.CANCEL,
                        request.reason(),
                        null,
                        idempotencyKey,
                        traceparent));
    }

    @PostMapping(path = "/{instanceId}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAccepted> replay(
            WorkflowRequestContext context,
            @PathVariable UUID instanceId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(name = TRACEPARENT_HEADER, required = false) String traceparent,
            @Valid @RequestBody ReplayInstanceRequest request) {
        return accepted(
                service.control(
                        context,
                        instanceId,
                        RuntimeControlAction.REPLAY,
                        request.reason(),
                        request.fromNodeId(),
                        idempotencyKey,
                        traceparent));
    }

    private static ResponseEntity<ActionAccepted> accepted(RuntimeControlResult result) {
        return ResponseEntity.accepted()
                .body(new ActionAccepted(result.actionId(), "accepted", result.acceptedAt()));
    }

    public record ControlInstanceRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReplayInstanceRequest(
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 80) @Pattern(regexp = "^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
                    String fromNodeId) {}

    public record ActionAccepted(UUID actionId, String status, Instant acceptedAt) {}
}
