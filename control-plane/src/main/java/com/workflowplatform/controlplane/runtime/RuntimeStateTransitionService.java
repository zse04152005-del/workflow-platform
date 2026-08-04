package com.workflowplatform.controlplane.runtime;

import java.util.Objects;
import org.springframework.stereotype.Service;

/** Single public entry point for all persisted runtime state transitions. */
@Service
public final class RuntimeStateTransitionService {

    private final RuntimeTransitionStore store;

    public RuntimeStateTransitionService(RuntimeTransitionStore transitionStore) {
        store = Objects.requireNonNull(transitionStore, "transitionStore");
    }

    public RuntimeTransitionResult transition(RuntimeTransitionCommand command) {
        Objects.requireNonNull(command, "command");
        String eventType = RuntimeTransitionPolicy.requireAllowed(command);
        return store.transition(command, eventType);
    }
}
