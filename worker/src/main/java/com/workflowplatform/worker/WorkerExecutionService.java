package com.workflowplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.payload.PayloadDigests;
import com.workflowplatform.payload.PayloadStorageException;
import com.workflowplatform.payload.TenantPayloadStore;
import com.workflowplatform.payload.UnavailableTenantPayloadStore;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    private static final int DEFAULT_INLINE_THRESHOLD_BYTES = 65_536;
    private static final int MAXIMUM_INLINE_THRESHOLD_BYTES = 64 * 1024 * 1024;
    private static final String INLINE_REFERENCE_PREFIX = "inline:";
    private static final String PAYLOAD_INTEGRITY_FAILED = "PAYLOAD_INTEGRITY_FAILED";

    private final WorkerTaskStore store;
    private final ObjectMapper objectMapper;
    private final List<WorkerNodeExecutor> executors;
    private final List<ExternalResultQueryHook> resultQueryHooks;
    private final TenantPayloadStore payloadStore;
    private final int inlineThresholdBytes;
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
                new UnavailableTenantPayloadStore(),
                DEFAULT_INLINE_THRESHOLD_BYTES,
                configuredConsumerName,
                configuredWorkerInstanceId);
    }

    public WorkerExecutionService(
            WorkerTaskStore taskStore,
            ObjectMapper jsonMapper,
            List<WorkerNodeExecutor> nodeExecutors,
            TenantPayloadStore tenantPayloadStore,
            String configuredConsumerName,
            String configuredWorkerInstanceId) {
        this(
                taskStore,
                jsonMapper,
                nodeExecutors,
                List.of(),
                tenantPayloadStore,
                DEFAULT_INLINE_THRESHOLD_BYTES,
                configuredConsumerName,
                configuredWorkerInstanceId);
    }

    WorkerExecutionService(
            WorkerTaskStore taskStore,
            ObjectMapper jsonMapper,
            List<WorkerNodeExecutor> nodeExecutors,
            List<ExternalResultQueryHook> externalResultQueryHooks,
            String configuredConsumerName,
            String configuredWorkerInstanceId) {
        this(
                taskStore,
                jsonMapper,
                nodeExecutors,
                externalResultQueryHooks,
                new UnavailableTenantPayloadStore(),
                DEFAULT_INLINE_THRESHOLD_BYTES,
                configuredConsumerName,
                configuredWorkerInstanceId);
    }

    @Autowired
    WorkerExecutionService(
            WorkerTaskStore taskStore,
            ObjectMapper jsonMapper,
            List<WorkerNodeExecutor> nodeExecutors,
            List<ExternalResultQueryHook> externalResultQueryHooks,
            TenantPayloadStore tenantPayloadStore,
            @Value("${workflow.object-storage.inline-threshold-bytes:65536}")
                    int configuredInlineThresholdBytes,
            @Value("${workflow.worker.consumer-name:workflow-worker}")
                    String configuredConsumerName,
            @Value("${workflow.worker.instance-id:worker-local}")
                    String configuredWorkerInstanceId) {
        store = Objects.requireNonNull(taskStore, "taskStore");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        executors = List.copyOf(nodeExecutors);
        resultQueryHooks = List.copyOf(externalResultQueryHooks);
        payloadStore = Objects.requireNonNull(tenantPayloadStore, "tenantPayloadStore");
        if (configuredInlineThresholdBytes < 1
                || configuredInlineThresholdBytes > MAXIMUM_INLINE_THRESHOLD_BYTES) {
            throw new IllegalArgumentException("payload inline threshold is invalid");
        }
        inlineThresholdBytes = configuredInlineThresholdBytes;
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
        WorkerTaskStore.ClaimedTask executableTask;
        try {
            executableTask = resolvePayload(task);
        } catch (PayloadStorageException exception) {
            store.complete(task, workerInstanceId, payloadFailure(exception));
            return ProcessResult.PROCESSED;
        }
        WorkerNodeExecutor executor =
                executors.stream()
                        .filter(candidate -> candidate.supports(executableTask.nodeType()))
                        .findFirst()
                        .orElse(null);
        WorkerTaskStore.NodeExecutionResult executionResult =
                executor == null
                        ? WorkerTaskStore.NodeExecutionResult.failure(
                                "NODE_TYPE_UNSUPPORTED", "node executor is not registered")
                        : executeBounded(executor, executableTask);
        WorkerTaskStore.NodeExecutionResult result =
                externalizeOutput(task, resolveAmbiguousResult(executableTask, executionResult));
        store.complete(task, workerInstanceId, result);
        return ProcessResult.PROCESSED;
    }

    private WorkerTaskStore.NodeExecutionResult externalizeOutput(
            WorkerTaskStore.ClaimedTask task, WorkerTaskStore.NodeExecutionResult executionResult) {
        if (executionResult.outcome() != WorkerTaskStore.NodeOutcome.SUCCEEDED
                || !executionResult.outputReference().startsWith(INLINE_REFERENCE_PREFIX)) {
            return executionResult;
        }
        final byte[] output;
        try {
            output =
                    Base64.getUrlDecoder()
                            .decode(
                                    executionResult
                                            .outputReference()
                                            .substring(INLINE_REFERENCE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    PAYLOAD_INTEGRITY_FAILED, "inline output payload is invalid", false);
        }
        if (!executionResult.outputDigest().equals(PayloadDigests.sha256(output))) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    PAYLOAD_INTEGRITY_FAILED, "inline output payload digest mismatch", false);
        }
        if (output.length <= inlineThresholdBytes) {
            return executionResult;
        }
        try {
            TenantPayloadStore.StoredPayload stored =
                    payloadStore.put(
                            task.event().tenantId(),
                            output,
                            "application/json",
                            "runtime-30d",
                            Instant.now());
            if (!executionResult.outputDigest().equals(stored.digest())
                    || output.length != stored.sizeBytes()) {
                return WorkerTaskStore.NodeExecutionResult.failure(
                        PAYLOAD_INTEGRITY_FAILED, "object output payload metadata mismatch", false);
            }
            return WorkerTaskStore.NodeExecutionResult.success(stored.reference(), stored.digest());
        } catch (PayloadStorageException exception) {
            return payloadFailure(exception);
        }
    }

    private WorkerTaskStore.ClaimedTask resolvePayload(WorkerTaskStore.ClaimedTask task) {
        if (task.inputPayload() != null) {
            return task;
        }
        if (task.inputReference() == null) {
            throw new PayloadStorageException(
                    PayloadStorageException.Reason.INVALID_REFERENCE,
                    "task input snapshot is missing");
        }
        String payload =
                new String(
                        payloadStore.read(
                                task.event().tenantId(), task.inputReference(), task.inputDigest()),
                        StandardCharsets.UTF_8);
        return new WorkerTaskStore.ClaimedTask(
                task.event(),
                task.consumerName(),
                task.workflowInstanceId(),
                task.executionId(),
                task.nodeId(),
                task.nodeType(),
                task.attemptNo(),
                payload,
                task.inputReference(),
                task.inputDigest(),
                task.configSnapshot(),
                task.dispatchToken(),
                task.leaseGeneration(),
                task.leaseExpiresAt());
    }

    private static WorkerTaskStore.NodeExecutionResult payloadFailure(
            PayloadStorageException exception) {
        return switch (exception.reason()) {
            case NOT_FOUND ->
                    WorkerTaskStore.NodeExecutionResult.failure(
                            "PAYLOAD_NOT_FOUND", "object payload is missing", false);
            case BACKEND ->
                    WorkerTaskStore.NodeExecutionResult.failure(
                            "PAYLOAD_STORAGE_UNAVAILABLE",
                            "object payload storage is unavailable",
                            true);
            case ACCESS_DENIED ->
                    WorkerTaskStore.NodeExecutionResult.failure(
                            "PAYLOAD_ACCESS_DENIED", "object payload access was denied", false);
            case INVALID_REFERENCE, INTEGRITY, RETENTION ->
                    WorkerTaskStore.NodeExecutionResult.failure(
                            PAYLOAD_INTEGRITY_FAILED,
                            "object payload integrity verification failed",
                            false);
        };
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
