package com.workflowplatform.worker.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.worker.WorkerTaskStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Plans a bounded retry from the immutable retry policy frozen into a node snapshot. */
final class WorkerRetryPlanner {

    private static final BigDecimal TWO = BigDecimal.valueOf(2L);
    private static final Duration MAXIMUM_DELAY = Duration.ofDays(1);
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(30);
    private static final String RETRY_FIELD_PREFIX = "retry ";

    private final ObjectMapper objectMapper;

    WorkerRetryPlanner(ObjectMapper jsonMapper) {
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    WorkerTaskStore.NodeExecutionResult plan(
            WorkerTaskStore.ClaimedTask task,
            WorkerTaskStore.NodeExecutionResult result,
            Instant firstAttemptStartedAt,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(firstAttemptStartedAt, "firstAttemptStartedAt");
        Objects.requireNonNull(now, "now");
        if (result.outcome() != WorkerTaskStore.NodeOutcome.FAILED || !result.retryable()) {
            return result;
        }
        JsonNode policy = retryPolicy(task.configSnapshot());
        if (!policy.isObject()) {
            return terminal(result);
        }
        int maximumAttempts = requiredInt(policy, "maxAttempts", 1, 10);
        if (task.attemptNo() >= maximumAttempts) {
            return terminal(result);
        }
        Duration elapsed = Duration.between(firstAttemptStartedAt, now);
        if (elapsed.isNegative()) {
            throw new IllegalStateException("retry elapsed time cannot be negative");
        }
        Duration maximumWindow = optionalDuration(policy, "maxElapsedTime");
        if (maximumWindow != null && elapsed.compareTo(maximumWindow) >= 0) {
            return terminal(result);
        }
        Duration delay =
                calculatedDelay(policy, task.event().nodeInstanceId().toString(), task.attemptNo());
        Duration retryAfter = result.retryAfter();
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        if (delay.compareTo(MAXIMUM_DELAY) > 0) {
            delay = MAXIMUM_DELAY;
        }
        if (maximumWindow != null && elapsed.plus(delay).compareTo(maximumWindow) > 0) {
            return terminal(result);
        }
        return WorkerTaskStore.NodeExecutionResult.retryWait(
                result.errorCode(), result.errorSummaryRedacted(), now.plus(delay));
    }

    private Duration calculatedDelay(JsonNode policy, String stableSeed, int completedAttempts) {
        Duration initial = requiredDuration(policy, "initialDelay");
        String strategy = policy.path("strategy").asText("");
        BigDecimal delayMillis = BigDecimal.valueOf(initial.toMillis());
        switch (strategy) {
            case "fixed" -> {
                // The initial delay is used for every retry.
            }
            case "linear" -> {
                Duration increment = requiredDuration(policy, "increment");
                delayMillis =
                        delayMillis.add(
                                BigDecimal.valueOf(increment.toMillis())
                                        .multiply(BigDecimal.valueOf(completedAttempts - 1L)));
            }
            case "exponential" -> {
                BigDecimal multiplier = requiredDecimal(policy, "multiplier");
                delayMillis = delayMillis.multiply(multiplier.pow(completedAttempts - 1));
            }
            default -> throw new IllegalArgumentException("retry strategy is unsupported");
        }
        Duration maximum = optionalDuration(policy, "maxDelay");
        if (maximum != null && delayMillis.compareTo(BigDecimal.valueOf(maximum.toMillis())) > 0) {
            delayMillis = BigDecimal.valueOf(maximum.toMillis());
        }
        BigDecimal jitterRatio = optionalDecimal(policy, "jitterRatio", BigDecimal.ZERO);
        if (jitterRatio.compareTo(BigDecimal.ZERO) < 0
                || jitterRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("retry jitterRatio is outside its bounds");
        }
        BigDecimal jitterFactor =
                BigDecimal.ONE.add(
                        jitterRatio.multiply(
                                BigDecimal.valueOf(stableJitter(stableSeed, completedAttempts))));
        long millis =
                delayMillis
                        .multiply(jitterFactor)
                        .max(BigDecimal.ONE)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
        return Duration.ofMillis(millis);
    }

    private double stableJitter(String stableSeed, int completedAttempts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(stableSeed.getBytes(StandardCharsets.UTF_8));
            digest.update(Integer.toString(completedAttempts).getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest.digest()).getLong() & Long.MAX_VALUE;
            return BigDecimal.valueOf(value)
                    .divide(BigDecimal.valueOf(Long.MAX_VALUE), 16, RoundingMode.HALF_UP)
                    .multiply(TWO)
                    .subtract(BigDecimal.ONE)
                    .doubleValue();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode retryPolicy(String snapshot) {
        try {
            return objectMapper.readTree(snapshot).path("_retry");
        } catch (IOException exception) {
            throw new IllegalArgumentException("node retry snapshot is invalid JSON", exception);
        }
    }

    private static WorkerTaskStore.NodeExecutionResult terminal(
            WorkerTaskStore.NodeExecutionResult result) {
        return WorkerTaskStore.NodeExecutionResult.failure(
                result.errorCode(), result.errorSummaryRedacted(), false);
    }

    private static int requiredInt(JsonNode policy, String field, int minimum, int maximum) {
        int value = policy.path(field).asInt(Integer.MIN_VALUE);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    RETRY_FIELD_PREFIX + field + " is outside its bounds");
        }
        return value;
    }

    private static Duration requiredDuration(JsonNode policy, String field) {
        Duration value = optionalDuration(policy, field);
        if (value == null) {
            throw new IllegalArgumentException(RETRY_FIELD_PREFIX + field + " is required");
        }
        return value;
    }

    private static Duration optionalDuration(JsonNode policy, String field) {
        String text = policy.path(field).asText("");
        if (text.isBlank()) {
            return null;
        }
        Duration value = Duration.parse(text);
        if (value.isNegative() || value.isZero() || value.compareTo(MAXIMUM_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    RETRY_FIELD_PREFIX + field + " is outside its bounds");
        }
        return value;
    }

    private static BigDecimal requiredDecimal(JsonNode policy, String field) {
        if (!policy.hasNonNull(field) || !policy.path(field).isNumber()) {
            throw new IllegalArgumentException(RETRY_FIELD_PREFIX + field + " is required");
        }
        BigDecimal value = policy.path(field).decimalValue();
        if ("multiplier".equals(field)
                && (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.TEN) > 0)) {
            throw new IllegalArgumentException("retry multiplier is outside its bounds");
        }
        return value;
    }

    private static BigDecimal optionalDecimal(
            JsonNode policy, String field, BigDecimal defaultValue) {
        return policy.hasNonNull(field) ? requiredDecimal(policy, field) : defaultValue;
    }
}
