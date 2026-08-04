package com.workflowplatform.controlplane.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Deterministic retry delay calculator with bounded jitter and Retry-After support. */
public final class RetryPolicyCalculator {

    public Optional<Duration> nextDelay(
            RetryPolicy policy,
            int completedAttempts,
            Duration elapsed,
            Duration retryAfter,
            RandomGenerator random) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(random, "random");
        if (completedAttempts >= policy.maximumAttempts()
                || elapsed.compareTo(policy.maximumWindow()) >= 0) {
            return Optional.empty();
        }
        long multiplier =
                switch (policy.strategy()) {
                    case FIXED -> 1L;
                    case LINEAR -> completedAttempts + 1L;
                    case EXPONENTIAL -> 1L << Math.min(completedAttempts, 20);
                };
        Duration calculated = policy.baseDelay().multipliedBy(multiplier);
        if (calculated.compareTo(policy.maximumDelay()) > 0) {
            calculated = policy.maximumDelay();
        }
        double jitter = (random.nextDouble() * 2.0 - 1.0) * policy.jitterRatio();
        long jitteredMillis = Math.max(0L, Math.round(calculated.toMillis() * (1.0 + jitter)));
        Duration result = Duration.ofMillis(jitteredMillis);
        if (retryAfter != null && retryAfter.compareTo(result) > 0) {
            result = retryAfter;
        }
        if (elapsed.plus(result).compareTo(policy.maximumWindow()) > 0) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    public record RetryPolicy(
            Strategy strategy,
            Duration baseDelay,
            Duration maximumDelay,
            int maximumAttempts,
            Duration maximumWindow,
            double jitterRatio) {
        public RetryPolicy {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(baseDelay, "baseDelay");
            Objects.requireNonNull(maximumDelay, "maximumDelay");
            Objects.requireNonNull(maximumWindow, "maximumWindow");
            if (baseDelay.isNegative()
                    || maximumDelay.compareTo(baseDelay) < 0
                    || maximumAttempts < 1
                    || maximumWindow.isNegative()
                    || jitterRatio < 0.0
                    || jitterRatio > 1.0) {
                throw new IllegalArgumentException("invalid retry policy");
            }
        }
    }

    public enum Strategy {
        FIXED,
        LINEAR,
        EXPONENTIAL
    }
}
