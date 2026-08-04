package com.workflowplatform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WorkerApplicationTests {

    @Test
    void contextLoadsTestConfiguration(
            @Autowired ApplicationContext applicationContext, @Autowired Environment environment) {
        assertNotNull(applicationContext, "The worker Spring context must start");
        assertEquals(
                "test",
                environment.getProperty("workflow.environment"),
                "Tests must activate the isolated test configuration profile");
        assertEquals(
                "workflow-test-only",
                environment.getProperty("workflow.object-storage.secret-key"),
                "Tests must never inherit a local or production object-storage secret");
        assertEquals(
                "2",
                environment.getProperty("workflow.worker.concurrency"),
                "Tests must use the deterministic test worker concurrency");
    }
}
