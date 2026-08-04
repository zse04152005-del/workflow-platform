package com.workflowplatform.controlplane.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class ObservabilityConventionsTests {

    @Test
    void traceMetricAndLogConventionsAreStable() {
        assertEquals("runtime.trigger", ObservabilityConventions.spanName("runtime", "trigger"));
        assertEquals("queue_depth", ObservabilityConventions.metric("queue_depth"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ObservabilityConventions.metric("tenant_123_queue"));
        assertEquals(
                "<redacted>",
                ObservabilityConventions.sanitizeLog(Map.of("Authorization", "secret"))
                        .get("Authorization"));
    }

    @Test
    void shutdownStopsDispatchAndReportsDrain() {
        GracefulShutdownCoordinator coordinator = new GracefulShutdownCoordinator();
        coordinator.begin();
        assertFalse(coordinator.canDispatch());
        assertTrue(
                coordinator
                        .awaitDrain(
                                Instant.EPOCH,
                                Instant.EPOCH.plusSeconds(31),
                                Duration.ofSeconds(30),
                                1)
                        .forcedLeaseHandoff());
    }
}
