package com.workflowplatform.contracts.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable trigger common fields plus schema-versioned trigger configuration. */
public record WorkflowTrigger(String id, TriggerType type, Map<String, Object> configuration) {

    public WorkflowTrigger {
        id = DslModelSupport.requireLocalId(id, "trigger id");
        Objects.requireNonNull(type, "type");
        configuration = DslModelSupport.immutableJsonObject(configuration, "trigger configuration");
    }

    @Override
    public Map<String, Object> configuration() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
}
