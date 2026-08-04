/** Workflow instance state machine, DAG advancement and instance-control public API. */
@ModuleBoundary(
        value = "runtime",
        allowedDependencies = {"definition", "tenant", "security", "audit"})
package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.ModuleBoundary;
