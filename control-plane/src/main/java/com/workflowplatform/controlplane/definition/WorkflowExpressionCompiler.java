package com.workflowplatform.controlplane.definition;

/** Compiles all CEL and named-root JSONPath sites after graph and configuration validation. */
@FunctionalInterface
public interface WorkflowExpressionCompiler {

    CompiledWorkflowExpressions compile(ValidatedWorkflowConfiguration configuration);
}
