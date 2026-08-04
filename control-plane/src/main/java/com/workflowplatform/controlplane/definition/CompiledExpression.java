package com.workflowplatform.controlplane.definition;

import java.util.Objects;

/** Payload-free expression compilation metadata bound to a source SHA-256. */
public record CompiledExpression(
        ExpressionLanguage language,
        ExpressionValueType resultType,
        ExpressionValueType expectedType,
        String sourceSha256,
        int tokenCount,
        int astNodeCount,
        int estimatedEvaluationSteps) {

    public CompiledExpression {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        if (!sourceSha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("sourceSha256 must be lower-case SHA-256");
        }
        if (tokenCount < 1 || astNodeCount < 1 || estimatedEvaluationSteps < 1) {
            throw new IllegalArgumentException("Compiled expression metrics must be positive");
        }
    }
}
