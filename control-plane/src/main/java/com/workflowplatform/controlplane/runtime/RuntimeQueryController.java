package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-isolated instance, node and attempt query endpoints using keyset cursors. */
@RestController
@RequestMapping(path = "/api/v1/instances", produces = MediaType.APPLICATION_JSON_VALUE)
public final class RuntimeQueryController {

    private final RuntimeQueryService service;

    public RuntimeQueryController(RuntimeQueryService queryService) {
        service = Objects.requireNonNull(queryService, "queryService");
    }

    @GetMapping
    public RuntimeCursorPage<RuntimeQueryStore.InstanceView> instances(
            WorkflowRequestContext context,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.instances(context, cursor, limit);
    }

    @GetMapping("/{instanceId}")
    public RuntimeQueryStore.InstanceView instance(
            WorkflowRequestContext context, @PathVariable UUID instanceId) {
        return service.instance(context, instanceId);
    }

    @GetMapping("/{instanceId}/nodes")
    public RuntimeCursorPage<RuntimeQueryStore.NodeView> nodes(
            WorkflowRequestContext context,
            @PathVariable UUID instanceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.nodes(context, instanceId, cursor, limit);
    }

    @GetMapping("/{instanceId}/attempts")
    public RuntimeCursorPage<RuntimeQueryStore.AttemptView> attempts(
            WorkflowRequestContext context,
            @PathVariable UUID instanceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.attempts(context, instanceId, cursor, limit);
    }
}
