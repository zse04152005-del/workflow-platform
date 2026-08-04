package com.workflowplatform.controlplane.runtime;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.support.CronExpression;

/** Bounded Cron occurrence calculator with explicit misfire policy. */
public final class CronTriggerCalculator {

    private static final int MAXIMUM_CATCH_UP = 100;
    private static final int MAXIMUM_SEARCH_STEPS = 10_000;

    public List<OccurrenceDecision> dueOccurrences(
            String expression,
            ZoneId zone,
            Instant afterExclusive,
            Instant now,
            MisfirePolicy policy,
            int catchUpLimit) {
        if (expression == null
                || expression.isBlank()
                || zone == null
                || afterExclusive == null
                || now == null
                || policy == null
                || now.isBefore(afterExclusive)
                || catchUpLimit < 1
                || catchUpLimit > MAXIMUM_CATCH_UP) {
            throw new IllegalArgumentException("Cron calculation inputs are invalid");
        }
        CronExpression cron = CronExpression.parse(expression);
        ZonedDateTime cursor = afterExclusive.atZone(zone);
        List<Instant> due = new ArrayList<>();
        for (int step = 0; step < MAXIMUM_SEARCH_STEPS; step++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null || next.toInstant().isAfter(now)) {
                return decisions(due, policy, catchUpLimit);
            }
            due.add(next.toInstant());
            if (policy == MisfirePolicy.CATCH_UP_LIMITED && due.size() == catchUpLimit) {
                return decisions(due, policy, catchUpLimit);
            }
            cursor = next;
        }
        throw new IllegalStateException("Cron search budget exceeded");
    }

    public Instant nextScheduled(String expression, ZoneId zone, Instant afterExclusive) {
        if (expression == null || zone == null || afterExclusive == null) {
            throw new IllegalArgumentException("Cron schedule inputs are required");
        }
        ZonedDateTime next = CronExpression.parse(expression).next(afterExclusive.atZone(zone));
        if (next == null) {
            throw new IllegalArgumentException("Cron expression has no next occurrence");
        }
        return next.toInstant();
    }

    public boolean overlapAllowed(boolean configuredAllowOverlap, long activeInstanceCount) {
        if (activeInstanceCount < 0) {
            throw new IllegalArgumentException("active instance count cannot be negative");
        }
        return configuredAllowOverlap || activeInstanceCount == 0;
    }

    public Instant nextOccurrence(
            Instant scheduledAt,
            ZoneId zone,
            Duration interval,
            Instant now,
            MisfirePolicy policy) {
        if (scheduledAt == null
                || zone == null
                || interval == null
                || now == null
                || policy == null
                || interval.isZero()
                || interval.isNegative()) {
            throw new IllegalArgumentException("cron occurrence inputs are invalid");
        }
        ZonedDateTime candidate = scheduledAt.atZone(zone);
        while (!candidate.toInstant().isAfter(now)) {
            candidate = candidate.plus(interval);
            if (policy == MisfirePolicy.SKIP && candidate.toInstant().isBefore(now)) {
                continue;
            }
        }
        return candidate.toInstant();
    }

    public enum MisfirePolicy {
        SKIP,
        FIRE_ONCE,
        CATCH_UP_LIMITED
    }

    public record OccurrenceDecision(Instant scheduledAt, Decision decision) {}

    public enum Decision {
        FIRE,
        SKIP
    }

    private static List<OccurrenceDecision> decisions(
            List<Instant> due, MisfirePolicy policy, int catchUpLimit) {
        if (due.isEmpty()) {
            return List.of();
        }
        if (policy == MisfirePolicy.SKIP) {
            return List.of(new OccurrenceDecision(due.getLast(), Decision.SKIP));
        }
        if (policy == MisfirePolicy.FIRE_ONCE) {
            return List.of(new OccurrenceDecision(due.getLast(), Decision.FIRE));
        }
        return due.stream()
                .limit(catchUpLimit)
                .map(value -> new OccurrenceDecision(value, Decision.FIRE))
                .toList();
    }
}
