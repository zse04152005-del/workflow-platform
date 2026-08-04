package com.workflowplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Parses versioned task events, claims with fencing, executes one plugin and persists the result.
 */
@Service
public final class WorkerExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerExecutionService.class);
    private static final String TASK_READY_EVENT = "workflow.task.ready";
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(30);

    private final WorkerTaskStore store;
    private final ObjectMapper objectMapper;
    private final List<WorkerNodeExecutor> executors;
    private final List<ExternalResultQueryHook> resultQueryHooks;
    private final String consumerName;
    private final String workerInstanceId;
    private final ExecutorService nodeExecutions;

    public WorkerExecutionService(
            WorkerTaskStore taskStore,
            ObjectMapper jsonMapper,
            List<WorkerNodeExecutor> nodeExecutors,
            String configuredConsumerName,
            String configuredWorkerInstanceId) {
        this(
                taskStore,
                jsonMapper,
                nodeExecutors,
                List.of(),
                configuredConsumerName,
                configuredWorkerInstanceId);
    }

    @Autowired
    WorkerExecutionService(
            WorkerTaskStore taskStore,
            ObjectMapper jsonMapper,
            List<WorkerNodeExecutor> nodeExecutors,
            List<ExternalResultQueryHook> externalResultQueryHooks,
            @Value("${workflow.worker.consumer-name:workflow-worker}")
                    String configuredConsumerName,
            @Value("${workflow.worker.instance-id:worker-local}")
                    String configuredWorkerInstanceId) {
        store = Objects.requireNonNull(taskStore, "taskStore");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        executors = List.copyOf(nodeExecutors);
        resultQueryHooks = List.copyOf(externalResultQueryHooks);
        consumerName = requireIdentity(configuredConsumerName, "consumerName");
        workerInstanceId = requireIdentity(configuredWorkerInstanceId, "workerInstanceId");
        nodeExecutions = Executors.newVirtualThreadPerTaskExecutor();
    }

    public ProcessResult process(String payload) {
        WorkerTaskStore.TaskReadyEvent event = parse(payload);
        WorkerTaskStore.ClaimResult claim = store.claim(event, consumerName, workerInstanceId);
        if (claim.state() == WorkerTaskStore.ClaimState.DUPLICATE) {
            return ProcessResult.DUPLICATE;
        }
        if (claim.state() == WorkerTaskStore.ClaimState.STALE) {
            return ProcessResult.STALE;
        }
        WorkerTaskStore.ClaimedTask task = claim.task();
        WorkerNodeExecutor executor =
                executors.stream()
                        .filter(candidate -> candidate.supports(task.nodeType()))
                        .findFirst()
                        .orElse(null);
        WorkerTaskStore.NodeExecutionResult executionResult =
                executor == null
                        ? WorkerTaskStore.NodeExecutionResult.failure(
                                "NODE_TYPE_UNSUPPORTED", "node executor is not registered")
                        : executeBounded(executor, task);
        WorkerTaskStore.NodeExecutionResult result = resolveAmbiguousResult(task, executionResult);
        store.complete(task, workerInstanceId, result);
        return ProcessResult.PROCESSED;
    }

    private WorkerTaskStore.NodeExecutionResult executeBounded(
            WorkerNodeExecutor executor, WorkerTaskStore.ClaimedTask task) {
        Duration timeout;
        try {
            timeout = executionTimeout(task.configSnapshot());
        } catch (RuntimeException exception) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "NODE_CONFIG_INVALID", "node timeout configuration is invalid");
        }
        Future<WorkerTaskStore.NodeExecutionResult> future =
                nodeExecutions.submit(
                        () ->
                                Objects.requireNonNull(
                                        executor.execute(task), "node execution result"));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "NODE_TIMEOUT", "node execution exceeded its configured timeout", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "NODE_EXECUTION_INTERRUPTED", "node execution was interrupted", true);
        } catch (ExecutionException | RuntimeException exception) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "NODE_EXECUTION_UNEXPECTED", "node execution failed unexpectedly", true);
        }
    }

    private WorkerTaskStore.NodeExecutionResult resolveAmbiguousResult(
            WorkerTaskStore.ClaimedTask task, WorkerTaskStore.NodeExecutionResult executionResult) {
        if (executionResult.outcome() != WorkerTaskStore.NodeOutcome.FAILED
                || !executionResult.retryable()) {
            return executionResult;
        }
        for (ExternalResultQueryHook hook : resultQueryHooks) {
            try {
                if (!hook.supports(task.nodeType())) {
                    continue;
                }
                Optional<WorkerTaskStore.NodeExecutionResult> queried =
                        hook.query(task, executionResult);
                if (queried.isPresent() && isTerminalResolution(queried.get())) {
                    return queried.get();
                }
            } catch (RuntimeException exception) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "External result query failed safely for nodeId={} errorCode={} hookFailure={}",
                            task.nodeId(),
                            executionResult.errorCode(),
                            exception.getClass().getSimpleName());
                }
            }
        }
        return executionResult;
    }

    private static boolean isTerminalResolution(WorkerTaskStore.NodeExecutionResult result) {
        return result.outcome() == WorkerTaskStore.NodeOutcome.SUCCEEDED
                || (result.outcome() == WorkerTaskStore.NodeOutcome.FAILED && !result.retryable());
    }

    private Duration executionTimeout(String configSnapshot) {
        try {
            String configured = objectMapper.readTree(configSnapshot).path("_timeout").asText("");
            Duration timeout = configured.isBlank() ? DEFAULT_TIMEOUT : Duration.parse(configured);
            if (timeout.isNegative()
                    || timeout.isZero()
                    || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
                throw new IllegalArgumentException("node timeout is outside the supported range");
            }
            return timeout;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("node configuration is invalid JSON", exception);
        }
    }

    @PreDestroy
    void stopNodeExecutions() {
        nodeExecutions.shutdownNow();
    }

    private WorkerTaskStore.TaskReadyEvent parse(String payload) {
        try {
            JsonNode envelope = objectMapper.readTree(payload);
            String eventType = envelope.path("eventType").asText();
            int schemaVersion = envelope.path("schemaVersion").asInt();
            String traceId = envelope.path("traceId").asText();
            if (!TASK_READY_EVENT.equals(eventType)
                    || schemaVersion != 1
                    || !TRACE_ID.matcher(traceId).matches()
                    || traceId.chars().allMatch(character -> character == '0')) {
                throw new IllegalArgumentException("unsupported task event envelope");
            }
            return new WorkerTaskStore.TaskReadyEvent(
                    UUID.fromString(envelope.path("eventId").asText()),
                    UUID.fromString(envelope.path("tenantId").asText()),
                    UUID.fromString(envelope.path("payload").path("entityId").asText()),
                    eventType,
                    schemaVersion,
                    traceId);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalArgumentException("task event is invalid", exception);
        }
    }

    private static String requireIdentity(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,120}")) {
            throw new IllegalArgumentException(label + " has an invalid stable format");
        }
        return value;
    }

    public enum ProcessResult {
        PROCESSED,
        DUPLICATE,
        STALE
    }
}
