package com.workflowplatform.controlplane.definition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.api.error.GlobalProblemDetailsHandler;
import com.workflowplatform.controlplane.api.error.ProblemDetailsResponseFactory;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.controlplane.security.internal.WorkflowRequestContextArgumentResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

// G3-09 HTTP contract coverage for clone, import/export and immutable version diff.
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class WorkflowPortabilityControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "g3-09-controller-actor");
    private static final Instant TIMESTAMP = Instant.parse("2026-07-31T06:30:00Z");
    private static final String DEFINITION =
            """
            {"schemaVersion":1,"name":"portable-copy","catalogVersion":1,
             "triggers":[{"id":"manual","type":"manual"}],
             "nodes":[{"id":"start","type":"start","executorVersion":1},{"id":"end","type":"end","executorVersion":1}],
             "edges":[{"id":"edge","from":"start","to":"end","on":"success"}]}
            """;

    private WorkflowPortabilityService service;
    private ObjectMapper objectMapper;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowPortabilityService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new WorkflowPortabilityController(service, objectMapper))
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
    void clonesWithHttp201LocationEtagAndTrustedContext() throws Exception {
        when(service.cloneWorkflow(
                        eq(CONTEXT),
                        eq(WORKFLOW_ID),
                        eq(PROJECT_ID),
                        eq("portable-copy"),
                        eq(null),
                        eq(null),
                        any(),
                        eq("clone-key-2001")))
                .thenReturn(draft());

        mockMvc.perform(
                        post("/api/v1/workflows/{workflowId}/clone", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "clone-key-2001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetProjectId":"0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496",
                                          "name":"portable-copy"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/workflows/" + WORKFLOW_ID))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.name").value("portable-copy"))
                .andExpect(jsonPath("$.draftRevision").value(1));
    }

    @Test
    void exportsWithNoStoreAndSupportsPublishedVersionSelector() throws Exception {
        WorkflowExportBundle bundle =
                new WorkflowExportBundle(
                        WorkflowExportBundle.FORMAT,
                        1,
                        new WorkflowExportBundle.Source(
                                WORKFLOW_ID,
                                "published_version",
                                null,
                                2,
                                "a".repeat(64),
                                TIMESTAMP),
                        new WorkflowExportBundle.Workflow(
                                "portable-copy", null, objectMapper.readTree(DEFINITION)),
                        List.of());
        when(service.export(CONTEXT, WORKFLOW_ID, 2)).thenReturn(bundle);

        mockMvc.perform(
                        get("/api/v1/workflows/{workflowId}/export", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .queryParam("version", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.format").value(WorkflowExportBundle.FORMAT))
                .andExpect(jsonPath("$.source.version").value(2));
    }

    @Test
    void returnsBoundedVersionDiffAndRejectsMissingImportMappings() throws Exception {
        WorkflowVersionDiff diff =
                new WorkflowVersionDiff(
                        WORKFLOW_ID,
                        new WorkflowVersionDiff.VersionReference(1, "a".repeat(64), TIMESTAMP),
                        new WorkflowVersionDiff.VersionReference(
                                2, "b".repeat(64), TIMESTAMP.plusSeconds(1)),
                        1,
                        false,
                        List.of(
                                new WorkflowVersionDiff.Change(
                                        "replace",
                                        "/nodes/1/config/method",
                                        objectMapper.getNodeFactory().textNode("POST"),
                                        objectMapper.getNodeFactory().textNode("GET"))));
        when(service.diff(CONTEXT, WORKFLOW_ID, 1, 2)).thenReturn(diff);

        mockMvc.perform(
                        get("/api/v1/workflows/{workflowId}/versions/diff", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .queryParam("fromVersion", "1")
                                .queryParam("toVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.changeCount").value(1))
                .andExpect(jsonPath("$.changes[0].path").value("/nodes/1/config/method"));

        mockMvc.perform(
                        post("/api/v1/workflows/import")
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", "import-key-2001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "targetProjectId":"0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496",
                                          "name":"portable-copy",
                                          "bundle":{}
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));
    }

    @Test
    void rejectsMissingIdentityAndInvalidSelectors() throws Exception {
        mockMvc.perform(
                        get("/api/v1/workflows/{workflowId}/export", WORKFLOW_ID)
                                .queryParam("version", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        when(service.diff(CONTEXT, WORKFLOW_ID, 0, 2))
                .thenThrow(ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD));

        mockMvc.perform(
                        get("/api/v1/workflows/{workflowId}/versions/diff", WORKFLOW_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .queryParam("fromVersion", "0")
                                .queryParam("toVersion", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));
    }

    private WorkflowDraft draft() {
        return new WorkflowDraft(
                WORKFLOW_ID,
                PROJECT_ID,
                "portable-copy",
                null,
                "DRAFT",
                1,
                null,
                DEFINITION,
                "c".repeat(64),
                TIMESTAMP,
                TIMESTAMP);
    }
}
