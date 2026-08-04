package com.workflowplatform.payload;

import java.time.Duration;
import java.util.Arrays;

/** Frozen retention classes used in object metadata and lifecycle checks. */
public enum PayloadRetentionPolicy {
    TRANSIENT_7D("transient-7d", Duration.ofDays(1), Duration.ofDays(7)),
    RUNTIME_30D("runtime-30d", Duration.ofDays(7), Duration.ofDays(30)),
    AUDIT_365D("audit-365d", Duration.ofDays(30), Duration.ofDays(365));

    private final String configuredName;
    private final Duration archiveDelay;
    private final Duration retentionDuration;

    PayloadRetentionPolicy(String name, Duration archiveDelay, Duration retention) {
        configuredName = name;
        this.archiveDelay = archiveDelay;
        retentionDuration = retention;
    }

    public String externalName() {
        return configuredName;
    }

    public Duration archiveAfter() {
        return archiveDelay;
    }

    public Duration retainFor() {
        return retentionDuration;
    }

    public static PayloadRetentionPolicy parse(String value) {
        return Arrays.stream(values())
                .filter(policy -> policy.configuredName.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("unsupported payload retention class"));
    }
}
