package com.workflowplatform.controlplane.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** SPI for node executors; implementations return bounded output or classified failure. */
public interface NodePlugin {

    String type();

    NodeExecutionResult execute(NodeExecutionContext context);

    record NodeExecutionContext(String nodeId, Map<String, Object> input, Duration timeout) {
        public NodeExecutionContext {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(timeout, "timeout");
            input = Map.copyOf(input);
        }
    }

    record NodeExecutionResult(boolean success, Map<String, Object> output, NodeError error) {
        public NodeExecutionResult {
            Objects.requireNonNull(output, "output");
            output = Map.copyOf(output);
            if (success && error != null) {
                throw new IllegalArgumentException("successful result cannot carry an error");
            }
        }

        public static NodeExecutionResult success(Map<String, Object> output) {
            return new NodeExecutionResult(true, output, null);
        }

        public static NodeExecutionResult failure(String code, String summary, boolean retryable) {
            return new NodeExecutionResult(
                    false, Map.of(), new NodeError(code, summary, retryable));
        }
    }

    record NodeError(String code, String summaryRedacted, boolean retryable) {
        public NodeError {
            if (code == null
                    || code.isBlank()
                    || summaryRedacted == null
                    || summaryRedacted.isBlank()) {
                throw new IllegalArgumentException(
                        "node error code and redacted summary are required");
            }
        }
    }
}
