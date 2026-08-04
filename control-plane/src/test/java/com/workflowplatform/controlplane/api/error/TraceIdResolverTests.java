package com.workflowplatform.controlplane.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceIdResolverTests {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void prefersAValidW3cTraceparent() {
        String traceId =
                TraceIdResolver.resolve(
                        "00-" + TRACE_ID + "-00f067aa0ba902b7-01",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(TRACE_ID, traceId, "A valid W3C trace ID must take precedence");
    }

    @Test
    void fallsBackToAValidCorrelationId() {
        String correlationId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        assertEquals(
                correlationId,
                TraceIdResolver.resolve(
                        "00-00000000000000000000000000000000-00f067aa0ba902b7-01", correlationId),
                "An all-zero trace ID must fall back to correlation ID");
        assertEquals(
                correlationId,
                TraceIdResolver.resolve("00-" + TRACE_ID + "-0000000000000000-01", correlationId),
                "An all-zero span ID must invalidate traceparent");
    }

    @Test
    void generatesWhenInboundValuesAreMissingOrInvalid() {
        String generated = TraceIdResolver.resolve(null, "UPPERCASE-INVALID");
        String zeroFallback = TraceIdResolver.resolve(null, "00000000000000000000000000000000");

        assertTrue(generated.matches("^[0-9a-f]{32}$"), "Generated trace ID must be canonical");
        assertTrue(
                zeroFallback.matches("^[0-9a-f]{32}$"),
                "Zero correlation ID must produce a canonical trace ID");
        assertNotEquals(
                "00000000000000000000000000000000",
                generated,
                "Generated trace ID must not be all zero");
        assertNotEquals(generated, zeroFallback, "Independent generated trace IDs must differ");
    }
}
