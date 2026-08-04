package com.workflowplatform.controlplane.runtime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workflowplatform.controlplane.api.error.GlobalProblemDetailsHandler;
import com.workflowplatform.controlplane.api.error.ProblemDetailsResponseFactory;
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
class DeadLetterDispositionControllerTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID DEAD_LETTER_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID ACTION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final UUID TARGET_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496");
    private static final String IDEMPOTENCY_KEY = "dead-letter-key-001";
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "dead-letter-controller-test");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DeadLetterDispositionService service = mock(DeadLetterDispositionService.class);
        when(service.addNote(
                        eq(CONTEXT),
                        eq(DEAD_LETTER_ID),
                        anyString(),
                        eq(IDEMPOTENCY_KEY),
                        isNull()))
                .thenReturn(result("OPEN", null));
        when(service.redispatch(
                        eq(CONTEXT),
                        eq(DEAD_LETTER_ID),
                        anyString(),
                        eq(IDEMPOTENCY_KEY),
                        isNull()))
                .thenReturn(result("REQUEUED", TARGET_ID));
        mockMvc =
                MockMvcBuilders.standaloneSetup(new DeadLetterDispositionController(service))
                        .setCustomArgumentResolvers(new WorkflowRequestContextArgumentResolver())
                        .setControllerAdvice(
                                new GlobalProblemDetailsHandler(
                                        new ProblemDetailsResponseFactory()))
                        .build();
    }

    @Test
    void exposesAcceptedNoteAndRedispatchRoutes() throws Exception {
        accepted("notes", "{\"note\":\"investigate timeout\"}", "OPEN", null);
        accepted("redispatch", "{\"reason\":\"operator approved retry\"}", "REQUEUED", TARGET_ID);
    }

    @Test
    void requiresIdempotencyAndBoundedNonBlankBodies() throws Exception {
        mockMvc.perform(
                        post("/api/v1/dead-letters/{deadLetterId}/notes", DEAD_LETTER_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"investigate\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/v1/dead-letters/{deadLetterId}/redispatch", DEAD_LETTER_ID)
                                .requestAttr(WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_INVALID_FIELD"));
    }

    private void accepted(String action, String body, String expectedStatus, UUID expectedTarget)
            throws Exception {
        var result =
                mockMvc.perform(
                                post(
                                                "/api/v1/dead-letters/{deadLetterId}/" + action,
                                                DEAD_LETTER_ID)
                                        .requestAttr(
                                                WorkflowRequestContext.REQUEST_ATTRIBUTE, CONTEXT)
                                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.actionId").value(ACTION_ID.toString()))
                        .andExpect(jsonPath("$.status").value("accepted"))
                        .andExpect(jsonPath("$.deadLetterStatus").value(expectedStatus));
        if (expectedTarget == null) {
            result.andExpect(jsonPath("$.targetInstanceId").doesNotExist());
        } else {
            result.andExpect(jsonPath("$.targetInstanceId").value(expectedTarget.toString()));
        }
    }

    private static DeadLetterDispositionResult result(String status, UUID targetInstanceId) {
        return new DeadLetterDispositionResult(
                ACTION_ID,
                Instant.parse("2026-08-04T00:00:00Z"),
                false,
                DEAD_LETTER_ID,
                status,
                targetInstanceId);
    }
}
