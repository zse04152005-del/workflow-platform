package com.workflowplatform.controlplane.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stops new dispatch, drains active work and reports readiness during shutdown. */
public final class GracefulShutdownCoordinator {

    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean ready = new AtomicBoolean(true);

    public void begin() {
        accepting.set(false);
        ready.set(false);
    }

    public boolean canDispatch() {
        return accepting.get();
    }

    public ShutdownResult awaitDrain(
            Instant startedAt, Instant now, Duration grace, int activeLeases) {
        if (activeLeases == 0) {
            return new ShutdownResult(true, false, Duration.between(startedAt, now));
        }
        boolean expired = !startedAt.plus(grace).isAfter(now);
        return new ShutdownResult(!expired, expired, Duration.between(startedAt, now));
    }

    public boolean isReady() {
        return ready.get();
    }

    public record ShutdownResult(boolean drained, boolean forcedLeaseHandoff, Duration elapsed) {}
}
