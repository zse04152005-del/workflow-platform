package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class WorkflowValidationServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final String VALID_DEFINITION =
            """
            {
              "schemaVersion": 1,
              "name": "validate-test",
              "catalogVersion": 1,
              "triggers": [{"id": "manual", "type": "manual"}],
              "nodes": [
                {"id": "start", "type": "start", "executorVersion": 1},
                {"id": "end", "type": "end", "executorVersion": 1}
              ],
              "edges": [{"id": "edge", "from": "start", "to": "end", "on": "success"}]
            }
            """;
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "validate-test-actor");

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowDraftService drafts;
    private WorkflowValidationContextStore contexts;

    @BeforeEach
    void setUp() {
        drafts = mock(WorkflowDraftService.class);
        contexts = mock(WorkflowValidationContextStore.class);
        when(drafts.get(CONTEXT, WORKFLOW_ID)).thenReturn(draft(7, VALID_DEFINITION));
        when(contexts.load(CONTEXT, PROJECT_ID))
                .thenReturn(
                        new WorkflowConfigurationValidationContext(
                                TENANT_ID, PROJECT_ID, List.of(), Set.of()));
    }

    @Test
    void validatesTheCurrentRevisionAndASuppliedDefinition() throws Exception {
        WorkflowValidationService service = realService();

        WorkflowValidationResult current =
                service.validate(CONTEXT, WORKFLOW_ID, null, "validate-key-001");
        WorkflowValidationResult supplied =
                service.validate(
                        CONTEXT,
                        WORKFLOW_ID,
                        mapper.readTree(VALID_DEFINITION),
                        "validate-key-002");

        assertTrue(current.valid());
        assertEquals("current_draft", current.source());
        assertEquals(7L, current.draftRevision());
        assertEquals(64, current.checksum().length());
        assertEquals("supplied_definition", supplied.source());
        assertNull(supplied.draftRevision());
        assertEquals(current.checksum(), supplied.checksum());
        assertEquals(0, supplied.issueCount());
    }

    @Test
    void mapsParserAndNameFailuresAndStopsBeforeDependentStages() throws Exception {
        WorkflowGraphValidator graph = mock(WorkflowGraphValidator.class);
        WorkflowConfigurationValidator configuration = mock(WorkflowConfigurationValidator.class);
        WorkflowExpressionCompiler expressions = mock(WorkflowExpressionCompiler.class);
        WorkflowValidationService service = service(graph, configuration, expressions);

        WorkflowValidationResult parseFailure =
                service.validate(CONTEXT, WORKFLOW_ID, mapper.nullNode(), "validate-key-003");
        JsonNode mismatch = mapper.readTree(VALID_DEFINITION).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) mismatch).put("name", "other-name");
        WorkflowValidationResult nameFailure =
                service.validate(CONTEXT, WORKFLOW_ID, mismatch, "validate-key-004");

        assertEquals("DSL_PARSE_ROOT_NOT_OBJECT", parseFailure.issues().getFirst().code());
        assertEquals("/", parseFailure.issues().getFirst().path());
        assertNull(parseFailure.checksum());
        assertEquals("DSL_DEFINITION_NAME_MISMATCH", nameFailure.issues().getFirst().code());
        assertEquals("/name", nameFailure.issues().getFirst().path());
        assertEquals(64, nameFailure.checksum().length());
        verifyNoInteractions(graph, configuration, expressions, contexts);
    }

    @Test
    void mapsGraphPathsToNodeIdsAndCapsThePublicIssueList() throws Exception {
        WorkflowGraphValidator graph = mock(WorkflowGraphValidator.class);
        List<WorkflowGraphViolation> violations = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            violations.add(
                    new WorkflowGraphViolation(
                            WorkflowGraphValidationFailure.NODE_UNREACHABLE, "/nodes/1"));
        }
        when(graph.validate(any())).thenThrow(new WorkflowGraphValidationException(violations));
        WorkflowConfigurationValidator configuration = mock(WorkflowConfigurationValidator.class);
        WorkflowExpressionCompiler expressions = mock(WorkflowExpressionCompiler.class);

        WorkflowValidationResult result =
                service(graph, configuration, expressions)
                        .validate(CONTEXT, WORKFLOW_ID, null, "validate-key-005");

        assertFalse(result.valid());
        assertEquals(501, result.issueCount());
        assertEquals(500, result.issues().size());
        assertTrue(result.truncated());
        assertEquals("DSL_GRAPH_NODE_UNREACHABLE", result.issues().getFirst().code());
        assertEquals("end", result.issues().getFirst().nodeId());
        verifyNoInteractions(contexts, configuration, expressions);
    }

    @Test
    void mapsConfigurationAndExpressionFieldsWithDeterministicShortCircuiting() throws Exception {
        WorkflowGraphValidator graph = new DeterministicWorkflowGraphValidator();
        WorkflowConfigurationValidator configuration = mock(WorkflowConfigurationValidator.class);
        when(configuration.validate(any(), any()))
                .thenThrow(
                        new WorkflowConfigurationValidationException(
                                List.of(
                                        new WorkflowConfigurationViolation(
                                                WorkflowConfigurationValidationFailure
                                                        .CONNECTOR_USE_FORBIDDEN,
                                                "/nodes/0/config/connectorRef"))));
        WorkflowExpressionCompiler expressions = mock(WorkflowExpressionCompiler.class);
        WorkflowValidationResult configurationFailure =
                service(graph, configuration, expressions)
                        .validate(CONTEXT, WORKFLOW_ID, null, "validate-key-006");

        assertEquals(
                "DSL_CONFIGURATION_CONNECTOR_USE_FORBIDDEN",
                configurationFailure.issues().getFirst().code());
        assertEquals("start", configurationFailure.issues().getFirst().nodeId());
        verify(expressions, never()).compile(any());

        WorkflowConfigurationValidator validConfiguration =
                (validatedGraph, validationContext) ->
                        new ValidatedWorkflowConfiguration(
                                validatedGraph, java.util.Map.of(), java.util.Map.of());
        WorkflowExpressionCompiler failingExpressions = mock(WorkflowExpressionCompiler.class);
        when(failingExpressions.compile(any()))
                .thenThrow(
                        new WorkflowExpressionCompilationException(
                                List.of(
                                        new WorkflowExpressionViolation(
                                                WorkflowExpressionCompilationFailure
                                                        .OPERATION_FORBIDDEN,
                                                "/edges/0/when"))));
        WorkflowValidationResult expressionFailure =
                service(graph, validConfiguration, failingExpressions)
                        .validate(CONTEXT, WORKFLOW_ID, null, "validate-key-007");

        assertEquals(
                "DSL_EXPRESSION_OPERATION_FORBIDDEN", expressionFailure.issues().getFirst().code());
        assertEquals("/edges/0/when", expressionFailure.issues().getFirst().path());
        assertNull(expressionFailure.issues().getFirst().nodeId());
    }

    @Test
    void rejectsMissingOrInvalidKeysAndTreatsPersistedParseFailureAsIntegrityError() {
        WorkflowValidationService service = realService();

        ApiProblemException missing =
                assertThrows(
                        ApiProblemException.class,
                        () -> service.validate(CONTEXT, WORKFLOW_ID, null, null));
        assertEquals("COMMON_VALIDATION_MISSING_PARAMETER", missing.problemCode().name());
        assertThrows(
                ApiProblemException.class,
                () -> service.validate(CONTEXT, WORKFLOW_ID, null, "short"));

        when(drafts.get(CONTEXT, WORKFLOW_ID)).thenReturn(draft(8, "not-json"));
        assertThrows(
                IllegalStateException.class,
                () -> service.validate(CONTEXT, WORKFLOW_ID, null, "validate-key-008"));
    }

    @Test
    void preparesOnlyARevalidatedChecksumBoundCurrentDraftForPublication() {
        WorkflowValidationService service = realService();
        WorkflowDraft locked = publicationDraft(9, VALID_DEFINITION);

        WorkflowPublicationCandidate candidate = service.prepareForPublication(CONTEXT, locked);

        assertEquals(locked, candidate.draft());
        assertEquals(locked.draftChecksum(), candidate.parsed().checksum());
        assertNotNull(candidate.configuration());
        verify(contexts).load(CONTEXT, PROJECT_ID);
    }

    @Test
    void blocksPublicationWhenTheLockedDraftFailsValidationOrChecksumBinding() {
        WorkflowValidationService service = realService();
        String invalidGraph = VALID_DEFINITION.replace("\"to\": \"end\"", "\"to\": \"missing\"");
        WorkflowPublicationException invalid =
                assertThrows(
                        WorkflowPublicationException.class,
                        () ->
                                service.prepareForPublication(
                                        CONTEXT, publicationDraft(10, invalidGraph)));
        assertEquals(WorkflowPublicationException.Failure.VALIDATION_FAILED, invalid.failure());

        WorkflowDraft correct = publicationDraft(11, VALID_DEFINITION);
        WorkflowDraft corrupted =
                new WorkflowDraft(
                        correct.id(),
                        correct.projectId(),
                        correct.name(),
                        correct.description(),
                        correct.status(),
                        correct.draftRevision(),
                        correct.publishedVersion(),
                        correct.canonicalDefinition(),
                        "f".repeat(64),
                        correct.createdAt(),
                        correct.updatedAt());
        assertThrows(
                IllegalStateException.class,
                () -> service.prepareForPublication(CONTEXT, corrupted));
    }

    private WorkflowValidationService realService() {
        return service(
                new DeterministicWorkflowGraphValidator(),
                new DeterministicWorkflowConfigurationValidator(),
                new DeterministicWorkflowExpressionCompiler());
    }

    private WorkflowValidationService service(
            WorkflowGraphValidator graph,
            WorkflowConfigurationValidator configuration,
            WorkflowExpressionCompiler expressions) {
        return new WorkflowValidationService(
                drafts,
                new JacksonWorkflowDefinitionParser(),
                graph,
                contexts,
                configuration,
                expressions,
                mapper);
    }

    private static WorkflowDraft draft(long revision, String definition) {
        return new WorkflowDraft(
                WORKFLOW_ID,
                PROJECT_ID,
                "validate-test",
                null,
                "DRAFT",
                revision,
                null,
                definition,
                "a".repeat(64),
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static WorkflowDraft publicationDraft(long revision, String definition) {
        ParsedWorkflowDefinition parsed =
                new JacksonWorkflowDefinitionParser()
                        .parse(
                                definition.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                WorkflowDefinitionSourceFormat.JSON);
        return new WorkflowDraft(
                WORKFLOW_ID,
                PROJECT_ID,
                "validate-test",
                null,
                "DRAFT",
                revision,
                null,
                parsed.canonicalJson(),
                parsed.checksum(),
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
