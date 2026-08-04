package com.workflowplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.contracts.expression.DeterministicConditionEvaluator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Restart-safe baseline executors for Start, End, Condition and Delay nodes. */
@Component
public final class BuiltinWorkerNodeExecutor implements WorkerNodeExecutor {

    private static final int FIRST_ATTEMPT = 1;
    private static final Set<String> SUPPORTED = Set.of("start", "end", "condition", "delay");
    private final ObjectMapper objectMapper;
    private final DeterministicConditionEvaluator conditionEvaluator;

    public BuiltinWorkerNodeExecutor(ObjectMapper jsonMapper) {
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        conditionEvaluator = new DeterministicConditionEvaluator();
    }

    @Override
    public boolean supports(String nodeType) {
        return SUPPORTED.contains(nodeType);
    }

    @Override
    public WorkerTaskStore.NodeExecutionResult execute(WorkerTaskStore.ClaimedTask task) {
        return switch (task.nodeType()) {
            case "start", "end" ->
                    WorkerTaskStore.NodeExecutionResult.success(
                            "instance-input:" + task.workflowInstanceId(), task.inputDigest());
            case "condition" -> condition(task);
            case "delay" -> delay(task);
            default ->
                    WorkerTaskStore.NodeExecutionResult.failure(
                            "NODE_TYPE_UNSUPPORTED", "node executor is not registered");
        };
    }

    private WorkerTaskStore.NodeExecutionResult condition(WorkerTaskStore.ClaimedTask task) {
        try {
            JsonNode config = objectMapper.readTree(task.configSnapshot());
            String expression = config.path("expression").asText("");
            JsonNode input =
                    task.inputPayload() == null
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(task.inputPayload());
            boolean result = conditionEvaluator.evaluate(expression, input);
            String output = "{\"result\":" + result + "}";
            String reference =
                    "inline:"
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(output.getBytes(StandardCharsets.UTF_8));
            return WorkerTaskStore.NodeExecutionResult.success(reference, sha256(output));
        } catch (DeterministicConditionEvaluator.EvaluationException exception) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "CONDITION_EVALUATION_FAILED", "condition expression evaluation failed");
        } catch (java.io.IOException exception) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "NODE_CONFIG_INVALID", "condition configuration is invalid JSON");
        }
    }

    private WorkerTaskStore.NodeExecutionResult delay(WorkerTaskStore.ClaimedTask task) {
        if (task.attemptNo() > FIRST_ATTEMPT) {
            return WorkerTaskStore.NodeExecutionResult.success(
                    "delay-complete:" + task.workflowInstanceId(), sha256("delay-complete"));
        }
        try {
            JsonNode config = objectMapper.readTree(task.configSnapshot());
            String configuredDuration = config.path("duration").asText("");
            if (configuredDuration.isBlank()) {
                return WorkerTaskStore.NodeExecutionResult.failure(
                        "DELAY_DURATION_INVALID", "delay duration is missing");
            }
            Duration duration = Duration.parse(configuredDuration);
            if (duration.isNegative()
                    || duration.isZero()
                    || duration.compareTo(Duration.ofDays(1)) > 0) {
                return WorkerTaskStore.NodeExecutionResult.failure(
                        "DELAY_DURATION_INVALID",
                        "delay duration must be greater than PT0S and no more than P1D");
            }
            return WorkerTaskStore.NodeExecutionResult.waitingTimer(Instant.now().plus(duration));
        } catch (java.io.IOException | java.time.format.DateTimeParseException exception) {
            return WorkerTaskStore.NodeExecutionResult.failure(
                    "NODE_CONFIG_INVALID", "delay configuration is invalid JSON");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
