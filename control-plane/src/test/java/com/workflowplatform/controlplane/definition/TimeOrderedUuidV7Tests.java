package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class TimeOrderedUuidV7Tests {

    @Test
    void emitsRfc9562VersionSevenVariantAndMonotonicValues() {
        UUID first = TimeOrderedUuidV7.generate(1_700_000_000_000L);
        UUID second = TimeOrderedUuidV7.generate(1_700_000_000_000L);
        UUID clockRegressed = TimeOrderedUuidV7.generate(1_699_999_999_000L);

        assertEquals(7, first.version());
        assertEquals(2, first.variant());
        assertTrue(compareUnsigned(first, second) < 0);
        assertTrue(compareUnsigned(second, clockRegressed) < 0);
    }

    private static int compareUnsigned(UUID left, UUID right) {
        int most =
                Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return most != 0
                ? most
                : Long.compareUnsigned(
                        left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
