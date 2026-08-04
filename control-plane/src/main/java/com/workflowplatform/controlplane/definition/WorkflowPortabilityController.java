package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public clone/import/export and immutable-version difference endpoints. */
@RestController
@RequestMapping(path = "/api/v1/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
public final class WorkflowPortabilityController {

    private static final String NAME_PATTERN = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$";
    private static final String REFERENCE_PATTERN = "^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$";

    private final WorkflowPortabilityService service;
    private final ObjectMapper objectMapper;

    public WorkflowPortabilityController(
            WorkflowPortabilityService portabilityService, ObjectMapper jsonMapper) {
        service = Objects.requireNonNull(portabilityService, "portabilityService");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @PostMapping(path = "/{workflowId}/clone", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowDraftController.WorkflowDetailResponse> cloneWorkflow(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CloneWorkflowRequest request) {
        WorkflowDraft created =
                service.cloneWorkflow(
                        context,
                        workflowId,
                        request.targetProjectId(),
                        request.name(),
                        request.description(),
                        request.sourceVersion(),
                        mappings(request.resourceMappings()),
                        idempotencyKey);
        return created(created);
    }

    @GetMapping("/{workflowId}/export")
    public ResponseEntity<WorkflowExportBundle> exportWorkflow(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestParam(required = false) @Min(1) Integer version) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.export(context, workflowId, version));
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowDraftController.WorkflowDetailResponse> importWorkflow(
            WorkflowRequestContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ImportWorkflowRequest request) {
        WorkflowDraft created =
                service.importWorkflow(
                        context,
                        request.targetProjectId(),
                        request.name(),
                        request.description(),
                        request.bundle(),
                        mappings(request.resourceMappings()),
                        idempotencyKey);
        return created(created);
    }

    @GetMapping("/{workflowId}/versions/diff")
    public ResponseEntity<WorkflowVersionDiff> diffWorkflowVersions(
            WorkflowRequestContext context,
            @PathVariable UUID workflowId,
            @RequestParam @Min(1) int fromVersion,
            @RequestParam @Min(1) int toVersion) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.diff(context, workflowId, fromVersion, toVersion));
    }

    private ResponseEntity<WorkflowDraftController.WorkflowDetailResponse> created(
            WorkflowDraft draft) {
        return ResponseEntity.created(URI.create("/api/v1/workflows/" + draft.id()))
                .header(HttpHeaders.ETAG, StrongDraftEtag.from(draft))
                .body(WorkflowDraftController.toResponse(draft, objectMapper));
    }

    private static List<WorkflowPortabilityService.ReferenceMapping> mappings(
            List<ReferenceMappingRequest> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(
                        value ->
                                new WorkflowPortabilityService.ReferenceMapping(
                                        value.kind(),
                                        value.sourceReference(),
                                        value.targetReference()))
                .toList();
    }

    public record CloneWorkflowRequest(
            @NotNull UUID targetProjectId,
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = NAME_PATTERN) String name,
            @Size(max = 1000) String description,
            @Min(1) Integer sourceVersion,
            @Valid @Size(max = WorkflowPortabilityService.MAXIMUM_RESOURCE_MAPPINGS)
                    List<ReferenceMappingRequest> resourceMappings) {

        public CloneWorkflowRequest {
            if (resourceMappings != null) {
                resourceMappings = List.copyOf(resourceMappings);
            }
        }

        @Override
        public List<ReferenceMappingRequest> resourceMappings() {
            return resourceMappings == null ? null : List.copyOf(resourceMappings);
        }
    }

    public record ImportWorkflowRequest(
            @NotNull UUID targetProjectId,
            @NotNull @Size(min = 1, max = 120) @Pattern(regexp = NAME_PATTERN) String name,
            @Size(max = 1000) String description,
            @NotNull @Valid WorkflowExportBundle bundle,
            @NotNull @Valid @Size(max = WorkflowPortabilityService.MAXIMUM_RESOURCE_MAPPINGS)
                    List<ReferenceMappingRequest> resourceMappings) {

        public ImportWorkflowRequest {
            if (resourceMappings != null) {
                resourceMappings = List.copyOf(resourceMappings);
            }
        }

        @Override
        public List<ReferenceMappingRequest> resourceMappings() {
            return resourceMappings == null ? null : List.copyOf(resourceMappings);
        }
    }

    public record ReferenceMappingRequest(
            @NotNull @Pattern(regexp = "^(connector|secret)$") String kind,
            @NotNull @Size(min = 3, max = 200) @Pattern(regexp = REFERENCE_PATTERN)
                    String sourceReference,
            @NotNull @Size(min = 3, max = 200) @Pattern(regexp = REFERENCE_PATTERN)
                    String targetReference) {}
}
