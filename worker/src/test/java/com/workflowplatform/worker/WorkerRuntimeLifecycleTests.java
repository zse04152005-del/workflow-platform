package com.workflowplatform.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class WorkerRuntimeLifecycleTests {

    @Test
    void startsOnceAndStopsCooperatively() {
        WorkerRuntimeLifecycle lifecycle = new WorkerRuntimeLifecycle();
        lifecycle.start();
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
    }
}
