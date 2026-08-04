package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class RuntimeReliabilityTests {

    private static final UUID TASK_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");

    @Test
    void retryStrategiesRespectWindowAttemptsAndRetryAfter() {
        RetryPolicyCalculator calculator = new RetryPolicyCalculator();
        var policy =
                new RetryPolicyCalculator.RetryPolicy(
                        RetryPolicyCalculator.Strategy.EXPONENTIAL,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(1),
                        5,
                        Duration.ofMinutes(10),
                        0.0);
        Duration delay =
                calculator
                        .nextDelay(
                                policy,
                                2,
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(30),
                                RandomGeneratorFactory.of("L64X128MixRandom").create(501L))
                        .orElseThrow();
        assertEquals(Duration.ofSeconds(30), delay);
        assertTrue(
                calculator
                        .nextDelay(
                                policy,
                                5,
                                Duration.ZERO,
                                null,
                                RandomGeneratorFactory.of("L64X128MixRandom").create(502L))
                        .isEmpty());
    }

    @Test
    void expiredLeaseRecoveryKeepsCountersSeparate() {
        LeaseRecoveryService service = new LeaseRecoveryService();
        LeaseRecoveryService.Recovery recovery =
                service.recover(
                                List.of(
                                        new LeaseRecoveryService.LeaseRow(
                                                TASK_ID, Instant.EPOCH, 3, 2, 7)),
                                Instant.EPOCH.plusSeconds(1),
                                10)
                        .getFirst();
        assertEquals(4, recovery.dispatchCount());
        assertEquals(2, recovery.attemptNo());
        assertEquals(8, recovery.nextLeaseGeneration());
    }

    @Test
    void deadLetterRedactsAndReplayDoesNotRewriteHistory() {
        DeadLetterService deadLetters = new DeadLetterService();
        DeadLetterService.DeadLetter letter =
                deadLetters.record(
                        TASK_ID,
                        "HTTP_TIMEOUT",
                        "password=do-not-store\nrequest timed out",
                        Instant.EPOCH);
        assertTrue(letter.summaryRedacted().contains("<redacted>"));
        assertEquals(1, deadLetters.redispatch(letter.id()).redispatchCount());

        UUID attempt = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
        ReplayService.ReplayPlan replay =
                new ReplayService().asNewInstance(INSTANCE_ID, List.of(attempt));
        assertNotEquals(replay.sourceInstanceId(), replay.targetInstanceId());
        assertThrows(
                UnsupportedOperationException.class,
                () -> replay.historicalAttemptIds().add(UUID.randomUUID()));
    }

    @Test
    void pauseResumeCancelAndPayloadDigestAreExplicit() {
        InstanceControlService control = new InstanceControlService();
        control.register(INSTANCE_ID);
        assertEquals("PAUSED", control.pause(INSTANCE_ID).status());
        assertEquals("RUNNING", control.resume(INSTANCE_ID).status());
        assertTrue(control.cancel(INSTANCE_ID).cooperativeCancel());

        PayloadPolicy payloads = new PayloadPolicy(4);
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        PayloadPolicy.PayloadReference reference = payloads.reference(content, "runtime-30d");
        assertTrue(reference.reference().startsWith("object:sha256:"));
        payloads.verify(content, reference);
        assertThrows(
                IllegalArgumentException.class,
                () -> payloads.verify("other".getBytes(StandardCharsets.UTF_8), reference));
    }
}
