package com.workflowplatform.controlplane.security;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic audit redaction for before/after maps and log-safe strings. */
public final class AuditRedactor {

    private static final Set<String> SENSITIVE =
            Set.of("password", "token", "secret", "authorization", "cookie");

    public Map<String, Object> redact(Map<String, Object> values) {
        Map<String, Object> result = new TreeMap<>();
        values.forEach(
                (key, value) ->
                        result.put(
                                key,
                                SENSITIVE.contains(key.toLowerCase(java.util.Locale.ROOT))
                                        ? "<redacted>"
                                        : clean(value)));
        return Map.copyOf(result);
    }

    private static Object clean(Object value) {
        return value instanceof String text ? text.replaceAll("[\\r\\n\\t]", " ") : value;
    }
}
