package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Validate API for the current draft or a supplied transient definition. */
@RestController
@RequestMapping(path = "/api/v1/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
public final class WorkflowValidationController {

    private final WorkflowValidationService service;

    public WorkflowValidationController(WorkflowValidationService validationService) {
        service = validationService;
    }

    @PostMapping(path = "/{workflowId}/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WorkflowValidationResult validate(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ValidateWorkflowRequest request) {
        return service.validate(context, workflowId, request.definition(), idempotencyKey);
    }

    public record ValidateWorkflowRequest(JsonNode definition) {}
}
