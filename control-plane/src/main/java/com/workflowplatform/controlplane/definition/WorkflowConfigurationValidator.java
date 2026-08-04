package com.workflowplatform.controlplane.definition;

/** Validates and types node/trigger parameters after deterministic graph validation. */
@FunctionalInterface
public interface WorkflowConfigurationValidator {

    ValidatedWorkflowConfiguration validate(
            ValidatedWorkflowGraph graph, WorkflowConfigurationValidationContext context);
}
