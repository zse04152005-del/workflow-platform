package com.workflowplatform.controlplane.runtime;

/** Trusted component identity used to authorize a runtime state transition. */
public enum RuntimeTransitionActor {
    RUNTIME("runtime", "SERVICE"),
    SCHEDULER("scheduler", "SYSTEM"),
    WORKER("worker", "WORKER");

    private final String wireNameValue;
    private final String auditTypeValue;

    RuntimeTransitionActor(String wireName, String auditType) {
        this.wireNameValue = wireName;
        this.auditTypeValue = auditType;
    }

    public String wireName() {
        return wireNameValue;
    }

    public String auditType() {
        return auditTypeValue;
    }
}
