package com.workflowplatform.controlplane.tenant;

import com.workflowplatform.controlplane.runtime.RuntimePublicProbe;

/** Intentional test-only undeclared module dependency. */
public final class TenantToRuntimePublicViolation {

    private final RuntimePublicProbe runtimeDependency = new RuntimePublicProbe();

    public RuntimePublicProbe runtimePublicProbe() {
        return runtimeDependency;
    }
}
