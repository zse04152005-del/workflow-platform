package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Stable keyset query service for tenant-isolated instance, node and attempt APIs. */
@Service
public final class RuntimeQueryService {

    private static final int CURSOR_PART_COUNT = 2;
    private final RuntimeQueryStore store;

    public RuntimeQueryService() {
        store = null;
    }

    @Autowired
    public RuntimeQueryService(RuntimeQueryStore queryStore) {
        store = Objects.requireNonNull(queryStore, "queryStore");
    }

    public RuntimeQueryStore.InstanceView instance(
            WorkflowRequestContext context, UUID instanceId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(instanceId, "instanceId");
        return requireStore()
                .findInstance(context, instanceId)
                .orElseThrow(() -> ApiProblemException.of(ProblemCode.COMMON_NOT_FOUND_RESOURCE));
    }

    public RuntimeCursorPage<RuntimeQueryStore.InstanceView> instances(
            WorkflowRequestContext context, String cursor, int limit) {
        requireQuery(context, limit);
        return persistentPage(
                requireStore().instances(context, boundary(cursor), limit + 1), limit);
    }

    public RuntimeCursorPage<RuntimeQueryStore.NodeView> nodes(
            WorkflowRequestContext context, UUID instanceId, String cursor, int limit) {
        requireQuery(context, limit);
        Objects.requireNonNull(instanceId, "instanceId");
        instance(context, instanceId);
        return persistentPage(
                requireStore().nodes(context, instanceId, boundary(cursor), limit + 1), limit);
    }

    public RuntimeCursorPage<RuntimeQueryStore.AttemptView> attempts(
            WorkflowRequestContext context, UUID instanceId, String cursor, int limit) {
        requireQuery(context, limit);
        Objects.requireNonNull(instanceId, "instanceId");
        instance(context, instanceId);
        return persistentPage(
                requireStore().attempts(context, instanceId, boundary(cursor), limit + 1), limit);
    }

    public RuntimeCursorPage<RuntimeRow> page(List<RuntimeRow> rows, String cursor, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        RuntimeQueryStore.CursorBoundary decoded = boundary(cursor);
        List<RuntimeRow> sorted =
                rows.stream()
                        .filter(row -> decoded == null || before(row, decoded))
                        .sorted(
                                Comparator.comparing(RuntimeRow::createdAt)
                                        .reversed()
                                        .thenComparing(RuntimeRow::id, Comparator.reverseOrder()))
                        .limit((long) limit + 1)
                        .toList();
        boolean hasMore = sorted.size() > limit;
        List<RuntimeRow> items = hasMore ? sorted.subList(0, limit) : sorted;
        String next = hasMore ? encode(items.getLast()) : null;
        return new RuntimeCursorPage<>(items, next, hasMore);
    }

    private static boolean before(RuntimeRow row, RuntimeQueryStore.CursorBoundary cursor) {
        int timeComparison = row.createdAt().compareTo(cursor.createdAt());
        return timeComparison < 0 || (timeComparison == 0 && row.id().compareTo(cursor.id()) < 0);
    }

    private static String encode(RuntimeQueryStore.CursorItem row) {
        String value = row.createdAt() + "|" + row.id();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static RuntimeQueryStore.CursorBoundary boundary(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded =
                    new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != CURSOR_PART_COUNT) {
                throw new IllegalArgumentException("invalid runtime cursor");
            }
            return new RuntimeQueryStore.CursorBoundary(
                    Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw ApiProblemException.causedBy(
                    ProblemCode.COMMON_VALIDATION_INVALID_FIELD, exception);
        }
    }

    private RuntimeQueryStore requireStore() {
        return Objects.requireNonNull(store, "persistent runtime query store");
    }

    private static void requireQuery(WorkflowRequestContext context, int limit) {
        Objects.requireNonNull(context, "context");
        if (limit < 1 || limit > 200) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
    }

    private static <T extends RuntimeQueryStore.CursorItem> RuntimeCursorPage<T> persistentPage(
            List<T> fetched, int limit) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? fetched.subList(0, limit) : fetched;
        String next = hasMore ? encode(items.getLast()) : null;
        return new RuntimeCursorPage<>(items, next, hasMore);
    }

    public record RuntimeRow(UUID id, String entityType, String status, Instant createdAt)
            implements RuntimeQueryStore.CursorItem {
        public RuntimeRow {
            if (id == null || entityType == null || status == null || createdAt == null) {
                throw new IllegalArgumentException("runtime row fields are required");
            }
        }
    }
}
