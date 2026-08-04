package com.workflowplatform.contracts.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable workflow input and output JSON Schema fragments. */
public record WorkflowParameters(
        Map<String, Object> inputSchema, Map<String, Object> outputSchema) {

    public WorkflowParameters {
        inputSchema = DslModelSupport.immutableJsonObject(inputSchema, "inputSchema");
        outputSchema = DslModelSupport.immutableJsonObject(outputSchema, "outputSchema");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outputSchema));
    }
}
