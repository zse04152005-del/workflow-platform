package com.workflowplatform.controlplane.runtime;

import java.util.List;
import java.util.Objects;

/** Stable keyset cursor page; callers never use OFFSET for runtime histories. */
public record RuntimeCursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    public RuntimeCursorPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("hasMore and nextCursor must agree");
        }
    }
}
