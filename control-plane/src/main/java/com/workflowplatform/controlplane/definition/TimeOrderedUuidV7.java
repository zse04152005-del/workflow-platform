package com.workflowplatform.controlplane.definition;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe UUIDv7 generator with monotonic 12-bit sequencing inside one millisecond. */
public final class TimeOrderedUuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong LAST_MILLI_AND_SEQUENCE = new AtomicLong();
    private static final long MAXIMUM_SEQUENCE = 0x0fffL;

    private TimeOrderedUuidV7() {}

    public static UUID generate() {
        return generate(Clock.systemUTC().millis());
    }

    static UUID generate(long currentMillis) {
        long state =
                LAST_MILLI_AND_SEQUENCE.updateAndGet(
                        previous -> nextState(previous, currentMillis));
        long timestamp = state >>> 12;
        long sequence = state & 0x0fffL;
        long mostSignificant = (timestamp << 16) | 0x7000L | sequence;
        long leastSignificant = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }

    private static long nextState(long previous, long currentMillis) {
        long previousMillis = previous >>> 12;
        long previousSequence = previous & 0x0fffL;
        long effectiveMillis = Math.max(currentMillis, previousMillis);
        if (effectiveMillis == previousMillis) {
            if (previousSequence == MAXIMUM_SEQUENCE) {
                return (effectiveMillis + 1) << 12;
            }
            return (effectiveMillis << 12) | (previousSequence + 1);
        }
        return effectiveMillis << 12;
    }
}
