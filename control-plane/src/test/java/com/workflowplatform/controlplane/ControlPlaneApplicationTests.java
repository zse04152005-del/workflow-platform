package com.workflowplatform.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ControlPlaneApplicationTests {

    @Test
    void contextLoadsTestConfiguration(
            @Autowired ApplicationContext applicationContext, @Autowired Environment environment) {
        assertNotNull(applicationContext, "The control-plane Spring context must start");
        assertEquals(
                "test",
                environment.getProperty("workflow.environment"),
                "Tests must activate the isolated test configuration profile");
        assertEquals(
                "workflow-test-only",
                environment.getProperty("workflow.database.password"),
                "Tests must never inherit a local or production database password");
        assertEquals(
                "http://127.0.0.1:8081/realms/workflow-platform-test",
                environment.getProperty("workflow.oidc.issuer"),
                "Tests must use the test-only OIDC issuer contract");
    }
}
