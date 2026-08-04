package com.workflowplatform.controlplane.runtime;

/** Runtime-owned persistence port; callers use {@link RuntimeStateTransitionService}. */
@FunctionalInterface
public interface RuntimeTransitionStore {

    RuntimeTransitionResult transition(RuntimeTransitionCommand command, String eventType);
}
