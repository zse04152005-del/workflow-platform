package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public workflow draft endpoints. Authentication populates a trusted request attribute. */
@RestController
@RequestMapping(path = "/api/v1/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
public final class WorkflowDraftController {

    private final WorkflowDraftService service;
    private final ObjectMapper objectMapper;

    public WorkflowDraftController(WorkflowDraftService draftService, ObjectMapper jsonMapper) {
        service = Objects.requireNonNull(draftService, "draftService");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowDetailResponse> create(
            WorkflowRequestContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowDraft created =
                service.create(
                        context,
                        request.projectId(),
                        request.name(),
                        request.description(),
                        request.definition(),
                        idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/workflows/" + created.id()))
                .header(HttpHeaders.ETAG, StrongDraftEtag.from(created))
                .body(toResponse(created, objectMapper));
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<WorkflowDetailResponse> get(
            WorkflowRequestContext context, @PathVariable UUID workflowId) {
        WorkflowDraft draft = service.get(context, workflowId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, StrongDraftEtag.from(draft))
                .body(toResponse(draft, objectMapper));
    }

    @PutMapping(path = "/{workflowId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowDetailResponse> update(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateDraftRequest request) {
        WorkflowDraft updated =
                service.update(
                        context,
                        workflowId,
                        request.definition(),
                        request.changeNote(),
                        idempotencyKey,
                        ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, StrongDraftEtag.from(updated))
                .body(toResponse(updated, objectMapper));
    }

    static WorkflowDetailResponse toResponse(WorkflowDraft draft, ObjectMapper objectMapper) {
        try {
            return new WorkflowDetailResponse(
                    draft.id(),
                    draft.projectId(),
                    draft.name(),
                    draft.description(),
                    draft.wireStatus(),
                    draft.draftRevision(),
                    draft.publishedVersion(),
                    objectMapper.readTree(draft.canonicalDefinition()),
                    draft.createdAt(),
                    draft.updatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted workflow definition is invalid", exception);
        }
    }

    public record CreateWorkflowRequest(
            @NotNull UUID projectId,
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
                    String name,
            @Size(max = 1000) String description,
            @NotNull JsonNode definition) {}

    public record UpdateDraftRequest(
            @NotNull JsonNode definition, @Size(max = 500) String changeNote) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowDetailResponse(
            UUID id,
            UUID projectId,
            String name,
            String description,
            String status,
            long draftRevision,
            Integer publishedVersion,
            JsonNode definition,
            Instant createdAt,
            Instant updatedAt) {}
}
