/** Tenant, project, membership and role-binding public API. */
@ModuleBoundary(
        value = "tenant",
        allowedDependencies = {"security", "audit"})
package com.workflowplatform.controlplane.tenant;

import com.workflowplatform.controlplane.ModuleBoundary;
