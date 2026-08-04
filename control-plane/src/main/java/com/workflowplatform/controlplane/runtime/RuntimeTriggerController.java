package com.workflowplatform.controlplane.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
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

/** Manual and authenticated HTTP trigger endpoints with first-response replay semantics. */
@RestController
@RequestMapping(path = "/api/v1/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
public final class RuntimeTriggerController {

    private final RuntimeTriggerService service;

    public RuntimeTriggerController(RuntimeTriggerService triggerService) {
        service = Objects.requireNonNull(triggerService, "triggerService");
    }

    @PostMapping(path = "/{workflowId}/instances", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TriggerResponse> manual(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestHeader(name = "Traceparent", required = false) String traceId,
            @Valid @RequestBody TriggerRequest request) {
        RuntimeTriggerResult result =
                service.manual(
                        context,
                        workflowId,
                        request.input(),
                        request.businessKey(),
                        idempotencyKey,
                        traceId);
        return ResponseEntity.status(result.responseStatus())
                .location(URI.create("/api/v1/instances/" + result.instanceId()))
                .body(TriggerResponse.from(result));
    }

    public record TriggerRequest(JsonNode input, @Size(max = 200) String businessKey) {}

    public record TriggerResponse(
            UUID instanceId,
            UUID workflowVersionId,
            int workflowVersion,
            String status,
            boolean replayed,
            String requestDigest) {

        static TriggerResponse from(RuntimeTriggerResult result) {
            return new TriggerResponse(
                    result.instanceId(),
                    result.workflowVersionId(),
                    result.workflowVersion(),
                    result.status(),
                    result.replayed(),
                    result.requestDigest());
        }
    }
}
