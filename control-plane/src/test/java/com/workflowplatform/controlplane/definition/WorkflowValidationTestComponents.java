package com.workflowplatform.controlplane.definition;

/** Test-only factory that preserves package-private production implementation boundaries. */
public final class WorkflowValidationTestComponents {

    private WorkflowValidationTestComponents() {}

    public static WorkflowDefinitionParser parser() {
        return new JacksonWorkflowDefinitionParser();
    }

    public static WorkflowGraphValidator graphValidator() {
        return new DeterministicWorkflowGraphValidator();
    }

    public static WorkflowConfigurationValidator configurationValidator() {
        return new DeterministicWorkflowConfigurationValidator();
    }

    public static WorkflowExpressionCompiler expressionCompiler() {
        return new DeterministicWorkflowExpressionCompiler();
    }
}
