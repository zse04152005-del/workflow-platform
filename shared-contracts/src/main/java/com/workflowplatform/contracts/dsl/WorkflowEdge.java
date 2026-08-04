package com.workflowplatform.contracts.dsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable directed edge with a bounded deterministic parameter mapping. */
public record WorkflowEdge(
        String id,
        String from,
        String to,
        EdgeTransition on,
        String when,
        List<ParameterMapping> mapping) {

    public WorkflowEdge {
        id = DslModelSupport.requireLocalId(id, "edge id");
        from = DslModelSupport.requireLocalId(from, "edge from");
        to = DslModelSupport.requireLocalId(to, "edge to");
        Objects.requireNonNull(on, "on");
        if (on == EdgeTransition.CONDITION) {
            when = DslModelSupport.requireSafeExpressionShape(when, "condition expression");
        } else if (when != null) {
            throw new IllegalArgumentException("Only condition edges may declare when");
        }
        mapping = DslModelSupport.immutableList(mapping, 0, 100, "mapping");
        Set<String> targets = new HashSet<>();
        for (ParameterMapping entry : mapping) {
            if (!targets.add(entry.target())) {
                throw new IllegalArgumentException("Duplicate mapping target: " + entry.target());
            }
        }
    }

    @Override
    public List<ParameterMapping> mapping() {
        return Collections.unmodifiableList(new ArrayList<>(mapping));
    }
}
