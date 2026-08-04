package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.UUID;

/** Loads a secret-free, tenant-scoped resource and authorization snapshot for validation. */
@FunctionalInterface
public interface WorkflowValidationContextStore {

    WorkflowConfigurationValidationContext load(WorkflowRequestContext context, UUID projectId);
}
