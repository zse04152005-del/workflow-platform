package com.workflowplatform.controlplane.definition;

/** Parses one explicitly typed UTF-8 workflow definition into a deterministic canonical result. */
@FunctionalInterface
public interface WorkflowDefinitionParser {

    ParsedWorkflowDefinition parse(byte[] source, WorkflowDefinitionSourceFormat format);
}
