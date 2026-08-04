package com.workflowplatform.contracts.dsl;

/** One deterministic edge output-to-input parameter mapping. */
public record ParameterMapping(String target, String expression) {

    public ParameterMapping {
        target = DslModelSupport.requireMappingTarget(target);
        expression = DslModelSupport.requireSafeExpressionShape(expression, "mapping expression");
    }
}
