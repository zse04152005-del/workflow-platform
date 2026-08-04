package com.workflowplatform.controlplane.definition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable G3-05 result containing a validated definition and expression compilation plan. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class CompiledWorkflowExpressions {

    private final ValidatedWorkflowConfiguration configuration;
    private final ExpressionSandboxPolicy sandboxPolicy;
    private final Map<String, CompiledExpression> expressions;

    CompiledWorkflowExpressions(
            ValidatedWorkflowConfiguration configuration,
            ExpressionSandboxPolicy sandboxPolicy,
            Map<String, CompiledExpression> expressions) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sandboxPolicy = Objects.requireNonNull(sandboxPolicy, "sandboxPolicy");
        Objects.requireNonNull(expressions, "expressions");
        this.expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
    }

    public ValidatedWorkflowConfiguration configuration() {
        return configuration;
    }

    public ExpressionSandboxPolicy sandboxPolicy() {
        return sandboxPolicy;
    }

    public Map<String, CompiledExpression> expressions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
    }
}
