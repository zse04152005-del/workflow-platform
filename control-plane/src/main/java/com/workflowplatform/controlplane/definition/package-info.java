/** Workflow draft, validation, immutable version and publication public API. */
@ModuleBoundary(
        value = "definition",
        allowedDependencies = {"tenant", "security", "audit"})
package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.ModuleBoundary;
