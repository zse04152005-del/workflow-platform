package com.workflowplatform.contracts.security;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded, deterministic redaction for error summaries and operator-authored notes. */
public final class SensitiveTextRedactor {

    private static final String REDACTED = "<redacted>";
    private static final Pattern CONTROL =
            Pattern.compile("[\\p{Cntrl}&&[^\\t\\r\\n]]|[\\t\\r\\n]");
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s{2,}");
    private static final Pattern CREDENTIAL =
            Pattern.compile(
                    "(?i)\\b(authorization|proxy-authorization|token|access[_-]?token|"
                            + "refresh[_-]?token|password|passwd|secret|api[_-]?key|cookie|"
                            + "set-cookie)\\b(\\s*[:=]\\s*)(?:bearer\\s+)?"
                            + "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern JWT =
            Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");

    private SensitiveTextRedactor() {}

    public static String redact(String value, int maximumLength) {
        if (maximumLength < REDACTED.length()) {
            throw new IllegalArgumentException("maximum redacted text length is too small");
        }
        if (value == null || value.isBlank()) {
            return REDACTED;
        }
        String normalized = CONTROL.matcher(value).replaceAll(" ");
        normalized = CREDENTIAL.matcher(normalized).replaceAll("$1$2" + REDACTED);
        normalized = JWT.matcher(normalized).replaceAll(REDACTED);
        normalized = REPEATED_WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        if (normalized.isBlank()) {
            return REDACTED;
        }
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }

    public static boolean containsRawSecret(String value) {
        Objects.requireNonNull(value, "value");
        return CREDENTIAL.matcher(value).find() || JWT.matcher(value).find();
    }
}
