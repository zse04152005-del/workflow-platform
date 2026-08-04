package com.workflowplatform.controlplane.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tenant-scoped fixed-window quota and concurrent-work backpressure. */
public final class TenantQuotaLimiter {

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public synchronized void acquire(UUID tenantId, Instant now, int limit, int activeLimit) {
        if (limit < 1 || activeLimit < 1) {
            throw new IllegalArgumentException("quota limits must be positive");
        }
        Window current = windows.get(tenantId);
        if (current == null || !current.windowStart().plusSeconds(60).isAfter(now)) {
            current = new Window(now, 0, 0);
        }
        if (current.requests() >= limit || current.active() >= activeLimit) {
            throw new IllegalStateException("tenant quota exceeded");
        }
        windows.put(
                tenantId,
                new Window(current.windowStart(), current.requests() + 1, current.active() + 1));
    }

    public synchronized void release(UUID tenantId) {
        Window current = windows.get(tenantId);
        if (current != null) {
            windows.put(
                    tenantId,
                    new Window(
                            current.windowStart(),
                            current.requests(),
                            Math.max(0, current.active() - 1)));
        }
    }

    private record Window(Instant windowStart, int requests, int active) {}
}
