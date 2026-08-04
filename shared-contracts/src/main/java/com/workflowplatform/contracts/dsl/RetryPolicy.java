package com.workflowplatform.contracts.dsl;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** Immutable bounded retry policy for HTTP nodes. */
@SuppressWarnings("PMD.ExhaustiveSwitchHasDefault")
public record RetryPolicy(
        int maxAttempts,
        RetryStrategy strategy,
        Duration initialDelay,
        Duration maxDelay,
        Duration maxElapsedTime,
        Duration increment,
        BigDecimal multiplier,
        BigDecimal jitterRatio) {

    private static final BigDecimal TEN = BigDecimal.TEN;

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        Objects.requireNonNull(strategy, "strategy");
        initialDelay = DslModelSupport.requirePositive(initialDelay, "initialDelay");
        maxDelay = DslModelSupport.optionalPositive(maxDelay, "maxDelay");
        maxElapsedTime = DslModelSupport.optionalPositive(maxElapsedTime, "maxElapsedTime");
        increment = DslModelSupport.optionalPositive(increment, "increment");
        if (maxDelay != null && maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be less than initialDelay");
        }
        if (maxElapsedTime != null && maxElapsedTime.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxElapsedTime must not be less than initialDelay");
        }
        if (multiplier != null
                && (multiplier.compareTo(BigDecimal.ONE) < 0 || multiplier.compareTo(TEN) > 0)) {
            throw new IllegalArgumentException("multiplier must be between 1 and 10");
        }
        if (jitterRatio != null
                && (jitterRatio.compareTo(BigDecimal.ZERO) < 0
                        || jitterRatio.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
        switch (strategy) {
            case FIXED -> {
                if (increment != null || multiplier != null) {
                    throw new IllegalArgumentException(
                            "fixed retry forbids increment and multiplier");
                }
            }
            case LINEAR -> {
                if (increment == null || multiplier != null) {
                    throw new IllegalArgumentException(
                            "linear retry requires increment and forbids multiplier");
                }
            }
            case EXPONENTIAL -> {
                if (multiplier == null || increment != null) {
                    throw new IllegalArgumentException(
                            "exponential retry requires multiplier and forbids increment");
                }
            }
            default -> throw new IllegalStateException("Unsupported retry strategy: " + strategy);
        }
    }
}
