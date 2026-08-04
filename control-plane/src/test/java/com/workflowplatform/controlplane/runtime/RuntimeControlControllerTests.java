package com.workflowplatform.controlplane.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workflowplatform.controlplane.api.error.GlobalProblemDetailsHandler;
import com.workflowplatform.controlplane.api.error.ProblemDetailsResponseFactory;
import com.workflowplatform.controlplane.runtime.RuntimeControlStore.RuntimeControlAction;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.controlplane.security.internal.WorkflowRequestContextArgumentResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class RuntimeControlControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID ACTION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final String IDEMPOTENCY_KEY = "control-key-001";
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "runtime-control-controller-test");

    private RuntimeControlService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeControlService.class);
        when(service.control(
                        eq(CONTEXT),
                        eq(INSTANCE_ID),
                        any(RuntimeControlAction.class),
                        any(String.class),
                        any(),
                        eq(IDEMPOTENCY_KEY),
                        isNull()))
                .thenReturn(
                        new RuntimeControlResult(
                                ACTION_ID,
                                Instant.parse("2026-08-03T00:00:00Z"),
                                false,
                                INSTANCE_ID,
                                "PAUSED"));
        mockMvc =
                MockMvcBuilders.standaloneSetup(new RuntimeControlController(service))
                        .setCustomArgumentResolvers(new WorkflowRequestContextArgumentResolver())
                        .setControllerAdvice(
                                new GlobalProblemDetailsHandler(
                                        new ProblemDetailsResponseFactory()))
                        .build();
    }

    @Test
    void exposesAllFourAcceptedControlRoutes() throws Exception {
        accepted("pause", "{\"reason\":\"maintenance\"}");
        accepted("resume", "{\"reason\":\"maintenance complete\"}");
        accepted("cancel", "{\"reason\":\"operator request\"}");
        accepted("replay", "{\"reason\":\"retry failed branch\",\"fromNodeId\":\"failed_node\"}");
    }

    @Test
    void requiresIdempotencyAndAValidRequestBody() throws Exception {
        mockMvc.perform(
                        post("/api/v1/instances/{instanceId}/pause", INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"maintenance\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/instances/{instanceId}/replay", INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"\",\"fromNodeId\":\"Invalid-Node\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));
    }

    private void accepted(String action, String body) throws Exception {
        mockMvc.perform(
                        post("/api/v1/instances/{instanceId}/" + action, INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionId").value(ACTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.acceptedAt").exists());
    }
}
