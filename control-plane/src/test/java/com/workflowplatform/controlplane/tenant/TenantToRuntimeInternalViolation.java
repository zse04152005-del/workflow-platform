package com.workflowplatform.controlplane.tenant;

import com.workflowplatform.controlplane.runtime.internal.RuntimeInternalProbe;

/** Intentional test-only architecture violation. */
public final class TenantToRuntimeInternalViolation {

    private final RuntimeInternalProbe runtimeDependency = new RuntimeInternalProbe();

    public RuntimeInternalProbe runtimeInternalProbe() {
        return runtimeDependency;
    }
}
