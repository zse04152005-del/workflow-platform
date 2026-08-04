package com.workflowplatform.controlplane.definition;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class WorkflowDraftControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final String DEFINITION =
            """
            {
              "schemaVersion": 1,
              "name": "controller-test",
              "catalogVersion": 1,
              "triggers": [{"id": "manual", "type": "manual"}],
              "nodes": [
                {"id": "start", "type": "start", "executorVersion": 1},
                {"id": "end", "type": "end", "executorVersion": 1}
              ],
              "edges": [{"id": "edge", "from": "start", "to": "end", "on": "success"}]
            }
            """;
    private static final String CREATE_BODY =
            """
            {
              "projectId": "0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494",
              "name": "controller-test",
              "description": "safe description",
              "definition": %s
            }
            """
                    .formatted(DEFINITION);
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "controller-test-actor");

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowDraftService service;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowDraftService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc =
                MockMvcBuilders.standaloneSetup(new WorkflowDraftController(service, mapper))
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
    void createsAJsonDraftWithLocationAndStrongEtag() throws Exception {
        WorkflowDraft draft = draft();
        when(service.create(
                        eq(CONTEXT),
                        eq(PROJECT_ID),
                        eq("controller-test"),
                        eq("safe description"),
                        any(JsonNode.class),
                        eq("create-key-001")))
                .thenReturn(draft);

        mockMvc.perform(
                        post("/api/v1/workflows")
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "create-key-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/workflows/" + WORKFLOW_ID))
                .andExpect(header().string("ETag", StrongDraftEtag.from(draft)))
                .andExpect(jsonPath("$.id").value(WORKFLOW_ID.toString()))
                .andExpect(jsonPath("$.draftRevision").value(1))
                .andExpect(jsonPath("$.definition.name").value("controller-test"))
                .andExpect(jsonPath("$.publishedVersion").doesNotExist());
    }

    @Test
    void ignoresUntrustedTenantHeadersAndRequiresTheTrustedContext() throws Exception {
        mockMvc.perform(
                        get("/api/v1/workflows/{workflowId}", WORKFLOW_ID)
                                .header("X-Tenant-Id", TENANT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTH_AUTHENTICATION_REQUIRED"));
    }

    @Test
    void mapsMissingAndStaleIfMatchToStandardPreconditionResponses() throws Exception {
        when(service.update(
                        eq(CONTEXT),
                        eq(WORKFLOW_ID),
                        any(JsonNode.class),
                        isNull(),
                        anyString(),
                        isNull()))
                .thenThrow(new StrongDraftEtagException(StrongDraftEtagException.Failure.REQUIRED));
        when(service.update(
                        eq(CONTEXT),
                        eq(WORKFLOW_ID),
                        any(JsonNode.class),
                        isNull(),
                        anyString(),
                        eq("\"stale\"")))
                .thenThrow(
                        new WorkflowDraftOperationException(
                                WorkflowDraftOperationException.Failure.PRECONDITION_FAILED));
        String updateBody = "{\"definition\":" + DEFINITION + '}';

        mockMvc.perform(
                        put("/api/v1/workflows/{workflowId}/draft", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "update-key-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("DEFINITION_PRECONDITION_REQUIRED_IF_MATCH"));

        mockMvc.perform(
                        put("/api/v1/workflows/{workflowId}/draft", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "update-key-002")
                                .header("If-Match", "\"stale\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updateBody))
                .andExpect(status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code").value("DEFINITION_PRECONDITION_FAILED_ETAG_MISMATCH"));
    }

    @Test
    void crossTenantStyleNotFoundNeverLeaksInternalDetails() throws Exception {
        when(service.get(CONTEXT, WORKFLOW_ID))
                .thenThrow(
                        new WorkflowDraftOperationException(
                                WorkflowDraftOperationException.Failure.NOT_FOUND));

        mockMvc.perform(
                        get("/api/v1/workflows/{workflowId}", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND_RESOURCE"))
                .andExpect(content().string(not(containsString(TENANT_ID.toString()))))
                .andExpect(content().string(not(containsString("workflow_definition"))));
    }

    private WorkflowDraft draft() throws Exception {
        JsonNode definition = mapper.readTree(DEFINITION);
        return new WorkflowDraft(
                WORKFLOW_ID,
                PROJECT_ID,
                "controller-test",
                "safe description",
                "DRAFT",
                1,
                null,
                mapper.writeValueAsString(definition),
                "a".repeat(64),
                Instant.parse("2026-07-31T00:00:00Z"),
                Instant.parse("2026-07-31T00:00:00Z"));
    }
}
