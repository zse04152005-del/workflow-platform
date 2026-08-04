package com.workflowplatform.contracts.dsl;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class DslModelSupport {

    static final int EXPRESSION_MAXIMUM_LENGTH = 2048;

    private static final int METADATA_MAXIMUM_SIZE = 32;

    private static final Pattern BUSINESS_NAME = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
    private static final Pattern LOCAL_ID = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$");
    private static final Pattern MAPPING_TARGET =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*$");
    private static final Pattern METADATA_KEY = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern FORBIDDEN_EXPRESSION =
            Pattern.compile("\\bnow\\s*\\(|\\brandom\\s*\\(|System\\.|Runtime\\.|class\\b");

    private DslModelSupport() {}

    static String requireBusinessName(String value) {
        return requirePattern(value, "name", BUSINESS_NAME, 120);
    }

    static String requireLocalId(String value, String label) {
        return requirePattern(value, label, LOCAL_ID, 80);
    }

    static String requireMappingTarget(String value) {
        return requirePattern(value, "mapping target", MAPPING_TARGET, 200);
    }

    static String requireSafeExpressionShape(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > EXPRESSION_MAXIMUM_LENGTH) {
            throw new IllegalArgumentException(
                    label + " must contain 1 to " + EXPRESSION_MAXIMUM_LENGTH + " characters");
        }
        if (FORBIDDEN_EXPRESSION.matcher(value).find()) {
            throw new IllegalArgumentException(label + " contains a non-deterministic token");
        }
        return value;
    }

    static String optionalText(String value, String label, int maximumLength) {
        if (value != null && value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    label + " must contain at most " + maximumLength + " characters");
        }
        return value;
    }

    static int requireAtLeast(int value, int minimum, String label) {
        if (value < minimum) {
            throw new IllegalArgumentException(label + " must be at least " + minimum);
        }
        return value;
    }

    static Duration requirePositive(Duration value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    static Duration optionalPositive(Duration value, String label) {
        return value == null ? null : requirePositive(value, label);
    }

    static <T> List<T> immutableList(List<T> values, int minimum, int maximum, String label) {
        Objects.requireNonNull(values, label);
        if (values.size() < minimum || values.size() > maximum) {
            throw new IllegalArgumentException(
                    label + " size must be between " + minimum + " and " + maximum);
        }
        for (T value : values) {
            Objects.requireNonNull(value, label + " member");
        }
        return List.copyOf(values);
    }

    static Map<String, Object> immutableJsonObject(Map<String, ?> source, String label) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), label + " key");
            copy.put(key, immutableJsonValue(entry.getValue(), label + "." + key));
        }
        return Collections.unmodifiableMap(copy);
    }

    static Map<String, Object> immutableMetadata(Map<String, ?> source) {
        Map<String, Object> copy = immutableJsonObject(source, "metadata");
        if (copy.size() > METADATA_MAXIMUM_SIZE) {
            throw new IllegalArgumentException("metadata must contain at most 32 entries");
        }
        for (Map.Entry<String, Object> entry : copy.entrySet()) {
            if (!METADATA_KEY.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("Invalid metadata key: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                throw new IllegalArgumentException("metadata values must be JSON scalars");
            }
        }
        return copy;
    }

    private static Object immutableJsonValue(Object value, String label) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float floating) {
            if (!Float.isFinite(floating)) {
                throw new IllegalArgumentException(label + " must be a finite JSON number");
            }
            return floating;
        }
        if (value instanceof Double floating) {
            if (!Double.isFinite(floating)) {
                throw new IllegalArgumentException(label + " must be a finite JSON number");
            }
            return floating;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(label + " keys must be strings");
                }
                typed.put(key, immutableJsonValue(entry.getValue(), label + "." + key));
            }
            return Collections.unmodifiableMap(typed);
        }
        if (value instanceof List<?> nested) {
            List<Object> copy = new ArrayList<>(nested.size());
            for (int index = 0; index < nested.size(); index++) {
                copy.add(immutableJsonValue(nested.get(index), label + "[" + index + "]"));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(label + " contains a non-JSON value");
    }

    private static String requirePattern(
            String value, String label, Pattern pattern, int maximumLength) {
        Objects.requireNonNull(value, label);
        if (value.length() > maximumLength || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value;
    }
}
