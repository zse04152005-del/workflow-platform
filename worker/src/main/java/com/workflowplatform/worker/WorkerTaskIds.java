package com.workflowplatform.worker;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Worker-local monotonic UUIDv7 generator for durable execution, audit and Outbox rows. */
public final class WorkerTaskIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong LAST_MILLI_AND_SEQUENCE = new AtomicLong();
    private static final long MAXIMUM_SEQUENCE = 0x0fffL;

    private WorkerTaskIds() {}

    public static UUID generate() {
        long currentMillis = Clock.systemUTC().millis();
        long state =
                LAST_MILLI_AND_SEQUENCE.updateAndGet(
                        previous -> nextState(previous, currentMillis));
        long timestamp = state >>> 12;
        long sequence = state & MAXIMUM_SEQUENCE;
        long mostSignificant = (timestamp << 16) | 0x7000L | sequence;
        long leastSignificant = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }

    private static long nextState(long previous, long currentMillis) {
        long previousMillis = previous >>> 12;
        long previousSequence = previous & MAXIMUM_SEQUENCE;
        long effectiveMillis = Math.max(currentMillis, previousMillis);
        if (effectiveMillis == previousMillis) {
            return previousSequence == MAXIMUM_SEQUENCE
                    ? (effectiveMillis + 1) << 12
                    : (effectiveMillis << 12) | (previousSequence + 1);
        }
        return effectiveMillis << 12;
    }
}
