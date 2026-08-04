package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint that publishes the current locked draft as one immutable version. */
@RestController
@RequestMapping(path = "/api/v1/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
public final class WorkflowPublicationController {

    private final WorkflowPublicationService service;

    public WorkflowPublicationController(WorkflowPublicationService publicationService) {
        service = publicationService;
    }

    @PostMapping(path = "/{workflowId}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublishedWorkflow> publish(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PublishWorkflowRequest request) {
        PublishedWorkflow published =
                service.publish(
                        context,
                        workflowId,
                        request.expectedDraftRevision(),
                        request.releaseNote(),
                        idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(published);
    }

    public record PublishWorkflowRequest(
            @NotNull @Min(1) Long expectedDraftRevision,
            @Size(min = 1, max = 1000) String releaseNote) {}
}
