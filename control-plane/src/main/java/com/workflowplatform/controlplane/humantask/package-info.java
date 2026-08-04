/** Human-task inbox, assignment, decision and timeout public API. */
@ModuleBoundary(
        value = "human-task",
        allowedDependencies = {"runtime", "tenant", "security", "audit"})
package com.workflowplatform.controlplane.humantask;

import com.workflowplatform.controlplane.ModuleBoundary;
