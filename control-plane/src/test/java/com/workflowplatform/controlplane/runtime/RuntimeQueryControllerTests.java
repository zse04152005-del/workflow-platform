package com.workflowplatform.controlplane.runtime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.api.error.GlobalProblemDetailsHandler;
import com.workflowplatform.controlplane.api.error.ProblemDetailsResponseFactory;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.controlplane.security.internal.WorkflowRequestContextArgumentResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class RuntimeQueryControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID INSTANCE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496");
    private static final UUID NODE_INSTANCE_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c497");
    private static final UUID ATTEMPT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c498");
    private static final UUID EXECUTION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c499");
    private static final Instant CREATED_AT = Instant.parse("2026-07-31T00:00:00Z");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "runtime-query-test");

    private RuntimeQueryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new RuntimeQueryController(service))
                        .setCustomArgumentResolvers(new WorkflowRequestContextArgumentResolver())
                        .setControllerAdvice(
                                new GlobalProblemDetailsHandler(
                                        new ProblemDetailsResponseFactory()))
                        .build();
    }

    @Test
    void exposesStableInstanceAndKeysetPageContracts() throws Exception {
        RuntimeQueryStore.InstanceView instance = instance();
        when(service.instances(eq(CONTEXT), isNull(), eq(1)))
                .thenReturn(new RuntimeCursorPage<>(List.of(instance), "next-page", true));
        when(service.instance(CONTEXT, INSTANCE_ID)).thenReturn(instance);

        mockMvc.perform(
                        get("/api/v1/instances")
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(INSTANCE_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.items[0].inputReference").value("payload://instance-input"))
                .andExpect(jsonPath("$.items[0].input").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("next-page"))
                .andExpect(jsonPath("$.hasMore").value(true));

        mockMvc.perform(
                        get("/api/v1/instances/{instanceId}", INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(WORKFLOW_ID.toString()))
                .andExpect(jsonPath("$.workflowVersionId").value(VERSION_ID.toString()))
                .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"));
    }

    @Test
    void exposesNodeAndAttemptHistoriesWithoutRawPayloads() throws Exception {
        RuntimeQueryStore.NodeView node = node();
        RuntimeQueryStore.AttemptView attempt = attempt();
        when(service.nodes(CONTEXT, INSTANCE_ID, "node-cursor", 50))
                .thenReturn(new RuntimeCursorPage<>(List.of(node), null, false));
        when(service.attempts(CONTEXT, INSTANCE_ID, "attempt-cursor", 50))
                .thenReturn(new RuntimeCursorPage<>(List.of(attempt), null, false));

        mockMvc.perform(
                        get("/api/v1/instances/{instanceId}/nodes", INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .param("cursor", "node-cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nodeId").value("condition"))
                .andExpect(jsonPath("$.items[0].inputDigest").value("a".repeat(64)))
                .andExpect(jsonPath("$.items[0].outputReference").value("payload://node-output"))
                .andExpect(jsonPath("$.items[0].output").doesNotExist());

        mockMvc.perform(
                        get("/api/v1/instances/{instanceId}/attempts", INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .param("cursor", "attempt-cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].executionId").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.items[0].errorCode").value("HTTP_UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.items[0].errorSummaryRedacted").value("upstream timeout"))
                .andExpect(content().string(not(containsString("Authorization"))));
    }

    @Test
    void returnsGenericNotFoundAndNeverTrustsTenantHeaders() throws Exception {
        when(service.instance(CONTEXT, INSTANCE_ID))
                .thenThrow(ApiProblemException.of(ProblemCode.COMMON_NOT_FOUND_RESOURCE));

        mockMvc.perform(
                        get("/api/v1/instances/{instanceId}", INSTANCE_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND_RESOURCE"))
                .andExpect(content().string(not(containsString(TENANT_ID.toString()))))
                .andExpect(content().string(not(containsString("workflow_instance"))));

        mockMvc.perform(
                        get("/api/v1/instances")
                                .header("X-Tenant-Id", TENANT_ID)
                                .header("X-Actor-Id", "spoofed"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"));
    }

    @Test
    void mapsInvalidPaginationInputsToPublicValidationErrors() throws Exception {
        when(service.instances(CONTEXT, "not-a-cursor", 50))
                .thenThrow(ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD));

        mockMvc.perform(
                        get("/api/v1/instances")
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));

        mockMvc.perform(
                        get("/api/v1/instances")
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));
    }

    private static RuntimeQueryStore.InstanceView instance() {
        return new RuntimeQueryStore.InstanceView(
                INSTANCE_ID,
                WORKFLOW_ID,
                VERSION_ID,
                3,
                "RUNNING",
                "order-1001",
                "payload://instance-input",
                "a".repeat(64),
                "0123456789abcdef0123456789abcdef",
                CREATED_AT,
                CREATED_AT,
                null);
    }

    private static RuntimeQueryStore.NodeView node() {
        return new RuntimeQueryStore.NodeView(
                NODE_INSTANCE_ID,
                INSTANCE_ID,
                "condition",
                "condition",
                "SUCCEEDED",
                1,
                "payload://instance-input",
                "a".repeat(64),
                "payload://node-output",
                "b".repeat(64),
                null,
                null,
                CREATED_AT);
    }

    private static RuntimeQueryStore.AttemptView attempt() {
        return new RuntimeQueryStore.AttemptView(
                ATTEMPT_ID,
                INSTANCE_ID,
                NODE_INSTANCE_ID,
                1,
                EXECUTION_ID,
                "FAILED",
                "payload://instance-input",
                "a".repeat(64),
                null,
                null,
                "HTTP_UPSTREAM_TIMEOUT",
                "upstream timeout",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT.plusSeconds(5));
    }
}
