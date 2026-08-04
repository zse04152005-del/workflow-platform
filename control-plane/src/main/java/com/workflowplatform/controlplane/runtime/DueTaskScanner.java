package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, deterministic due-task scan with process-local duplicate suppression. */
public final class DueTaskScanner {

    private static final Set<String> DUE_STATUSES = Set.of("READY", "RETRY_WAIT", "WAITING_TIMER");
    private final Set<UUID> claimed = ConcurrentHashMap.newKeySet();

    public List<DueTask> scanAndClaim(List<DueTask> tasks, Instant now, int limit) {
        if (now == null || limit < 1) {
            throw new IllegalArgumentException("scan time and positive limit are required");
        }
        List<DueTask> result = new ArrayList<>();
        tasks.stream()
                .filter(task -> DUE_STATUSES.contains(task.status()))
                .filter(task -> !task.nextScheduleAt().isAfter(now))
                .sorted(Comparator.comparing(DueTask::nextScheduleAt).thenComparing(DueTask::id))
                .forEach(
                        task -> {
                            if (result.size() < limit && claimed.add(task.id())) {
                                result.add(task);
                            }
                        });
        return List.copyOf(result);
    }

    public void release(UUID taskId) {
        claimed.remove(taskId);
    }

    public record DueTask(UUID id, String status, Instant nextScheduleAt) {
        public DueTask {
            if (id == null || status == null || nextScheduleAt == null) {
                throw new IllegalArgumentException("due task fields are required");
            }
        }
    }
}
