package com.workflowplatform.controlplane.definition;

/** Immutable compile-time and runtime limits for every published expression. */
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
public record ExpressionSandboxPolicy(
        int maximumTokens,
        int maximumAstNodes,
        int maximumNesting,
        int maximumPathSegments,
        int maximumEvaluationSteps,
        int timeoutMilliseconds,
        int maximumResultBytes,
        int maximumResultDepth,
        int maximumResultEntries) {

    public static final ExpressionSandboxPolicy DEFAULT =
            new ExpressionSandboxPolicy(256, 256, 32, 32, 512, 25, 262_144, 64, 4_096);

    public ExpressionSandboxPolicy {
        requirePositive(maximumTokens, "maximumTokens");
        requirePositive(maximumAstNodes, "maximumAstNodes");
        requirePositive(maximumNesting, "maximumNesting");
        requirePositive(maximumPathSegments, "maximumPathSegments");
        requirePositive(maximumEvaluationSteps, "maximumEvaluationSteps");
        requirePositive(timeoutMilliseconds, "timeoutMilliseconds");
        requirePositive(maximumResultBytes, "maximumResultBytes");
        requirePositive(maximumResultDepth, "maximumResultDepth");
        requirePositive(maximumResultEntries, "maximumResultEntries");
    }

    private static void requirePositive(int value, String label) {
        if (value < 1) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
