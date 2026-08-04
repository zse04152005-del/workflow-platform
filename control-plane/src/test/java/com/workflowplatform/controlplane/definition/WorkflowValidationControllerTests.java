package com.workflowplatform.controlplane.definition;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class WorkflowValidationControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "validation-controller-actor");

    private WorkflowValidationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowValidationService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new WorkflowValidationController(service))
                        .setCustomArgumentResolvers(new WorkflowRequestContextArgumentResolver())
                        .setControllerAdvice(
                                new GlobalProblemDetailsHandler(
                                        new ProblemDetailsResponseFactory()))
                        .build();
    }

    @Test
    void validatesTheCurrentDraftFromAnEmptyRequest() throws Exception {
        when(service.validate(eq(CONTEXT), eq(WORKFLOW_ID), isNull(), eq("validate-key-101")))
                .thenReturn(
                        new WorkflowValidationResult(
                                "current_draft", 12L, "a".repeat(64), true, 0, false, List.of()));

        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/validate", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "validate-key-101")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("current_draft"))
                .andExpect(jsonPath("$.draftRevision").value(12))
                .andExpect(jsonPath("$.checksum").value("a".repeat(64)))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.issueCount").value(0))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.issues").isEmpty());
    }

    @Test
    void returnsFieldAndNodeIssuesAsHttp200WithoutNullLeakage() throws Exception {
        when(service.validate(eq(CONTEXT), eq(WORKFLOW_ID), any(), eq("validate-key-102")))
                .thenReturn(
                        new WorkflowValidationResult(
                                "supplied_definition",
                                null,
                                "b".repeat(64),
                                false,
                                1,
                                false,
                                List.of(
                                        new WorkflowValidationIssue(
                                                "DSL_EXPRESSION_OPERATION_FORBIDDEN",
                                                "/nodes/2/config/expression",
                                                "decision",
                                                "Workflow expression could not be compiled.",
                                                "error"))));

        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/validate", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "validate-key-102")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"definition\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("supplied_definition"))
                .andExpect(jsonPath("$.draftRevision").doesNotExist())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issueCount").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("DSL_EXPRESSION_OPERATION_FORBIDDEN"))
                .andExpect(jsonPath("$.issues[0].path").value("/nodes/2/config/expression"))
                .andExpect(jsonPath("$.issues[0].nodeId").value("decision"))
                .andExpect(jsonPath("$.issues[0].severity").value("error"))
                .andExpect(content().string(not(containsString(TENANT_ID.toString()))));
    }

    @Test
    void requiresTrustedContextAndAValidIdempotencyHeader() throws Exception {
        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/validate", WORKFLOW_ID)
                                .header("Idempotency-Key", "validate-key-103")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"));

        when(service.validate(eq(CONTEXT), eq(WORKFLOW_ID), isNull(), isNull()))
                .thenThrow(ApiProblemException.of(ProblemCode.COMMON_VALIDATION_MISSING_PARAMETER));
        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/validate", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_MISSING_PARAMETER"));
    }
}
