package com.workflowplatform.controlplane.api.error;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TraceIdResolver {

    private static final Pattern TRACEPARENT =
            Pattern.compile("^(?!ff)[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIdResolver() {}

    static String resolve(String traceparent, String correlationId) {
        Matcher matcher = TRACEPARENT.matcher(valueOrEmpty(traceparent));
        if (matcher.matches()
                && !ZERO_TRACE_ID.equals(matcher.group(1))
                && !ZERO_SPAN_ID.equals(matcher.group(2))) {
            return matcher.group(1);
        }
        if (TRACE_ID.matcher(valueOrEmpty(correlationId)).matches()
                && !ZERO_TRACE_ID.equals(correlationId)) {
            return correlationId;
        }
        return generate();
    }

    private static String generate() {
        byte[] bytes = new byte[16];
        String traceId;
        do {
            RANDOM.nextBytes(bytes);
            traceId = HexFormat.of().formatHex(bytes);
        } while (ZERO_TRACE_ID.equals(traceId));
        return traceId;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
