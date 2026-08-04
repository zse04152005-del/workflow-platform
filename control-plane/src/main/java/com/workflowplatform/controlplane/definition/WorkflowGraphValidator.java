package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.WorkflowDefinition;

/** Validates deterministic graph invariants before node-specific compilation. */
@FunctionalInterface
public interface WorkflowGraphValidator {

    ValidatedWorkflowGraph validate(WorkflowDefinition definition);
}
