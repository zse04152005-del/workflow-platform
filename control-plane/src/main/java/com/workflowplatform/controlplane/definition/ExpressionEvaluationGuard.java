package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.definition.ExpressionSandboxException.Reason;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Per-evaluation guard for step, monotonic-time and JSON-compatible result limits. */
@SuppressWarnings({
    "PMD.AvoidFieldNameMatchingMethodName",
    "PMD.AvoidInstantiatingObjectsInLoops",
    "PMD.AvoidLiteralsInIfCondition",
    "PMD.AvoidReassigningLoopVariables"
})
public final class ExpressionEvaluationGuard {

    private final ExpressionSandboxPolicy policy;
    private final LongSupplier nanoTime;
    private final long startedAt;
    private int consumedSteps;

    private ExpressionEvaluationGuard(
            ExpressionSandboxPolicy policy, LongSupplier nanoTime, long startedAt) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startedAt = startedAt;
    }

    public static ExpressionEvaluationGuard start(ExpressionSandboxPolicy policy) {
        return start(policy, System::nanoTime);
    }

    static ExpressionEvaluationGuard start(ExpressionSandboxPolicy policy, LongSupplier nanoTime) {
        Objects.requireNonNull(nanoTime, "nanoTime");
        return new ExpressionEvaluationGuard(policy, nanoTime, nanoTime.getAsLong());
    }

    public void consumeSteps(int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be positive");
        }
        if (steps > policy.maximumEvaluationSteps() - consumedSteps) {
            throw new ExpressionSandboxException(Reason.STEP_LIMIT);
        }
        consumedSteps += steps;
        checkTime();
    }

    public void checkResult(Object value) {
        ResultBudget budget = new ResultBudget();
        measure(value, 0, budget);
        checkTime();
    }

    public int consumedSteps() {
        return consumedSteps;
    }

    private void checkTime() {
        long elapsed = nanoTime.getAsLong() - startedAt;
        long limit = policy.timeoutMilliseconds() * 1_000_000L;
        if (elapsed < 0 || elapsed > limit) {
            throw new ExpressionSandboxException(Reason.TIME_LIMIT);
        }
    }

    private void measure(Object value, int depth, ResultBudget budget) {
        if (depth > policy.maximumResultDepth()) {
            throw new ExpressionSandboxException(Reason.RESULT_DEPTH);
        }
        if (value == null) {
            budget.addBytes(4);
        } else if (value instanceof String text) {
            budget.addBytes(quotedBytes(text));
        } else if (value instanceof Boolean bool) {
            budget.addBytes(bool ? 4 : 5);
        } else if (isJsonNumber(value)) {
            budget.addBytes(value.toString().getBytes(StandardCharsets.UTF_8).length);
        } else if (value instanceof List<?> list) {
            measureList(list, depth, budget);
        } else if (value instanceof Map<?, ?> map) {
            measureMap(map, depth, budget);
        } else {
            throw new ExpressionSandboxException(Reason.RESULT_TYPE);
        }
    }

    private void measureList(List<?> values, int depth, ResultBudget budget) {
        budget.addEntries(values.size());
        budget.addBytes(2 + Math.max(0, values.size() - 1));
        for (Object value : values) {
            measure(value, depth + 1, budget);
        }
    }

    private void measureMap(Map<?, ?> values, int depth, ResultBudget budget) {
        budget.addEntries(values.size());
        budget.addBytes(2 + Math.max(0, values.size() - 1));
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ExpressionSandboxException(Reason.RESULT_TYPE);
            }
            budget.addBytes(quotedBytes(key) + 1);
            measure(entry.getValue(), depth + 1, budget);
        }
    }

    private static boolean isJsonNumber(Object value) {
        if (value instanceof Double number) {
            return Double.isFinite(number);
        }
        if (value instanceof Float number) {
            return Float.isFinite(number);
        }
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal;
    }

    private static int quotedBytes(String value) {
        int bytes = 2;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == '"' || codePoint == '\\') {
                bytes += 2;
            } else if (codePoint < 0x20) {
                bytes += 6;
            } else {
                bytes +=
                        new String(Character.toChars(codePoint))
                                .getBytes(StandardCharsets.UTF_8)
                                .length;
            }
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private final class ResultBudget {
        private int bytes;
        private int entries;

        private void addBytes(int increment) {
            if (increment > policy.maximumResultBytes() - bytes) {
                throw new ExpressionSandboxException(Reason.RESULT_BYTES);
            }
            bytes += increment;
        }

        private void addEntries(int increment) {
            if (increment > policy.maximumResultEntries() - entries) {
                throw new ExpressionSandboxException(Reason.RESULT_ENTRIES);
            }
            entries += increment;
        }
    }
}
