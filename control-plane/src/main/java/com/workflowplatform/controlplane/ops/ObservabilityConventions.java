package com.workflowplatform.controlplane.ops;

import java.util.Map;
import java.util.Set;

/** Low-cardinality trace/log/metric conventions shared by control plane and worker. */
public final class ObservabilityConventions {

    private static final Set<String> ALLOWED_METRICS =
            Set.of(
                    "workflow_trigger_total",
                    "task_duration_seconds",
                    "queue_depth",
                    "scheduler_lag_seconds",
                    "lease_expired_total",
                    "outbox_pending",
                    "dead_letter_total");
    private static final Set<String> SENSITIVE =
            Set.of("authorization", "cookie", "token", "password", "secret");

    private ObservabilityConventions() {}

    public static String spanName(String domain, String operation) {
        if (domain == null
                || operation == null
                || !domain.matches("[a-z_]+")
                || !operation.matches("[a-z_]+")) {
            throw new IllegalArgumentException("span names must be lowercase stable tokens");
        }
        return domain + "." + operation;
    }

    public static String metric(String name) {
        if (!ALLOWED_METRICS.contains(name)) {
            throw new IllegalArgumentException("metric is not in the low-cardinality catalog");
        }
        return name;
    }

    public static Map<String, String> sanitizeLog(Map<String, String> fields) {
        return fields.entrySet().stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry ->
                                        SENSITIVE.contains(
                                                        entry.getKey()
                                                                .toLowerCase(java.util.Locale.ROOT))
                                                ? "<redacted>"
                                                : entry.getValue().replaceAll("[\\r\\n\\t]", " ")));
    }
}
