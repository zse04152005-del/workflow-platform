package com.workflowplatform.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TestFixtureIdsTests {

    @Test
    void generatesStableIdentifiers() {
        assertEquals(
                FixtureIds.stableUuid("tenant-one"),
                FixtureIds.stableUuid("tenant-one"),
                "The same fixture name must produce the same UUID");
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FixtureIds.stableUuid(" "),
                "Blank fixture names must be rejected");
    }
}
