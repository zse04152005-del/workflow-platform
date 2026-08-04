package com.workflowplatform.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Produces stable identifiers for tests without sharing mutable global state. */
public final class FixtureIds {

    private FixtureIds() {}

    public static UUID stableUuid(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Fixture name must not be blank");
        }
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
