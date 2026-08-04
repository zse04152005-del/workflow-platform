package com.workflowplatform.contracts.dsl;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable node common fields plus schema-versioned node configuration. */
public record WorkflowNode(
        String id,
        NodeType type,
        int executorVersion,
        String displayName,
        Duration timeout,
        RetryPolicy retry,
        Map<String, Object> configuration) {

    public WorkflowNode {
        id = DslModelSupport.requireLocalId(id, "node id");
        Objects.requireNonNull(type, "type");
        executorVersion = DslModelSupport.requireAtLeast(executorVersion, 1, "executorVersion");
        displayName = DslModelSupport.optionalText(displayName, "displayName", 120);
        timeout = DslModelSupport.optionalPositive(timeout, "timeout");
        if (type != NodeType.HTTP && (timeout != null || retry != null)) {
            throw new IllegalArgumentException("Only HTTP nodes may declare timeout or retry");
        }
        configuration = DslModelSupport.immutableJsonObject(configuration, "node configuration");
    }

    @Override
    public Map<String, Object> configuration() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
}
