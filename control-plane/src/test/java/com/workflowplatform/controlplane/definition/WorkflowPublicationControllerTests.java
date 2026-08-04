package com.workflowplatform.controlplane.definition;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class WorkflowPublicationControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "publication-controller-actor");

    private WorkflowPublicationService service;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowPublicationService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc =
                MockMvcBuilders.standaloneSetup(new WorkflowPublicationController(service))
                        .setCustomArgumentResolvers(new WorkflowRequestContextArgumentResolver())
                        .setControllerAdvice(
                                new GlobalProblemDetailsHandler(
                                        new ProblemDetailsResponseFactory()))
                        .setValidator(validator)
                        .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void publishesAnImmutableVersionWithHttp201() throws Exception {
        when(service.publish(
                        eq(CONTEXT),
                        eq(WORKFLOW_ID),
                        eq(7L),
                        eq("ready for production"),
                        eq("publish-key-11111111")))
                .thenReturn(
                        new PublishedWorkflow(
                                WORKFLOW_ID,
                                3,
                                "a".repeat(64),
                                Instant.parse("2026-07-31T04:00:00Z")));

        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/publish", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "publish-key-11111111")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "expectedDraftRevision": 7,
                                          "releaseNote": "ready for production"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId").value(WORKFLOW_ID.toString()))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.checksum").value("a".repeat(64)))
                .andExpect(jsonPath("$.publishedAt").value("2026-07-31T04:00:00Z"));
    }

    @Test
    void rejectsMissingIdentityAndInvalidRevision() throws Exception {
        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/publish", WORKFLOW_ID)
                                .header("Idempotency-Key", "publish-key-22222222")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftRevision\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/publish", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "publish-key-33333333")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftRevision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));
    }

    @Test
    void mapsMissingIdempotencyAndPublicationConflictsToProblemDetails() throws Exception {
        when(service.publish(eq(CONTEXT), eq(WORKFLOW_ID), eq(1L), eq(null), eq(null)))
                .thenThrow(ApiProblemException.of(ProblemCode.COMMON_VALIDATION_MISSING_PARAMETER));
        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/publish", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftRevision\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_MISSING_PARAMETER"));

        when(service.publish(
                        eq(CONTEXT), eq(WORKFLOW_ID), eq(2L), eq(null), eq("publish-key-44444444")))
                .thenThrow(
                        new WorkflowPublicationException(
                                WorkflowPublicationException.Failure.REVISION_MISMATCH));
        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/publish", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "publish-key-44444444")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftRevision\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_CONFLICT_STATE"));
    }
}
