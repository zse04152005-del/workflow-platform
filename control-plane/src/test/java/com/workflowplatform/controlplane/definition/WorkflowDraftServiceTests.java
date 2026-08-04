package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.AvoidDuplicateLiterals"})
class WorkflowDraftServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final String MINIMAL_JSON =
            """
            {
              "schemaVersion": 1,
              "name": "service-test",
              "catalogVersion": 1,
              "triggers": [{"id": "manual", "type": "manual"}],
              "nodes": [
                {"id": "start", "type": "start", "executorVersion": 1},
                {"id": "end", "type": "end", "executorVersion": 1}
              ],
              "edges": [
                {"id": "edge", "from": "start", "to": "end", "on": "success"}
              ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowRequestContext context =
            new WorkflowRequestContext(TENANT_ID, "service-test-actor");
    private WorkflowDraftStore store;
    private WorkflowDraftService service;

    @BeforeEach
    void setUp() {
        store = mock(WorkflowDraftStore.class);
        service = new WorkflowDraftService(new JacksonWorkflowDefinitionParser(), store, mapper);
    }

    @Test
    void canonicalizesCreateAndBindsTheIdempotentRequest() throws Exception {
        WorkflowDraft expected = draft(1, "a".repeat(64));
        when(store.create(any())).thenReturn(expected);

        WorkflowDraft actual =
                service.create(
                        context,
                        PROJECT_ID,
                        "service-test",
                        "description",
                        definition(),
                        "create-key-001");

        assertEquals(expected, actual);
        ArgumentCaptor<WorkflowDraftStore.CreateCommand> command =
                ArgumentCaptor.forClass(WorkflowDraftStore.CreateCommand.class);
        verify(store).create(command.capture());
        assertEquals(7, command.getValue().workflowId().version());
        assertEquals("workflow:create", command.getValue().idempotency().operationScope());
        assertEquals(64, command.getValue().checksum().length());
    }

    @Test
    void rejectsMissingKeysAndDefinitionNameDriftBeforePersistence() throws Exception {
        ApiProblemException missing =
                assertThrows(
                        ApiProblemException.class,
                        () ->
                                service.create(
                                        context,
                                        PROJECT_ID,
                                        "service-test",
                                        null,
                                        definition(),
                                        null));
        assertEquals("COMMON_VALIDATION_MISSING_PARAMETER", missing.problemCode().name());

        JsonNode mismatched = definition().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) mismatched).put("name", "another-name");
        WorkflowDraftOperationException mismatch =
                assertThrows(
                        WorkflowDraftOperationException.class,
                        () ->
                                service.create(
                                        context,
                                        PROJECT_ID,
                                        "service-test",
                                        null,
                                        mismatched,
                                        "create-key-002"));
        assertEquals(
                WorkflowDraftOperationException.Failure.DEFINITION_NAME_MISMATCH,
                mismatch.failure());
    }

    @Test
    void enforcesTheIdempotencyKeyTransportBounds() throws Exception {
        WorkflowDraft expected = draft(1, "a".repeat(64));
        when(store.create(any())).thenReturn(expected);

        assertEquals(
                expected,
                service.create(
                        context, PROJECT_ID, "service-test", null, definition(), "k".repeat(128)));

        for (String invalid : new String[] {"k".repeat(7), "k".repeat(129)}) {
            ApiProblemException failure =
                    assertThrows(
                            ApiProblemException.class,
                            () ->
                                    service.create(
                                            context,
                                            PROJECT_ID,
                                            "service-test",
                                            null,
                                            definition(),
                                            invalid));
            assertEquals("COMMON_VALIDATION_INVALID_FIELD", failure.problemCode().name());
        }
    }

    @Test
    void updateRequiresThePathWorkflowInsideTheStrongEtag() throws Exception {
        WorkflowDraft other =
                new WorkflowDraft(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496"),
                        PROJECT_ID,
                        "service-test",
                        null,
                        "DRAFT",
                        1,
                        null,
                        "{}",
                        "a".repeat(64),
                        Instant.EPOCH,
                        Instant.EPOCH);

        WorkflowDraftOperationException mismatch =
                assertThrows(
                        WorkflowDraftOperationException.class,
                        () ->
                                service.update(
                                        context,
                                        WORKFLOW_ID,
                                        definition(),
                                        null,
                                        "update-key-001",
                                        StrongDraftEtag.from(other)));
        assertEquals(
                WorkflowDraftOperationException.Failure.PRECONDITION_FAILED, mismatch.failure());
    }

    @Test
    void readsOnlyThroughTheTenantScopedStore() {
        WorkflowDraft expected = draft(1, "a".repeat(64));
        when(store.find(context, WORKFLOW_ID)).thenReturn(Optional.of(expected));

        assertEquals(expected, service.get(context, WORKFLOW_ID));

        when(store.find(context, WORKFLOW_ID)).thenReturn(Optional.empty());
        WorkflowDraftOperationException missing =
                assertThrows(
                        WorkflowDraftOperationException.class,
                        () -> service.get(context, WORKFLOW_ID));
        assertEquals(WorkflowDraftOperationException.Failure.NOT_FOUND, missing.failure());
    }

    private JsonNode definition() throws Exception {
        return mapper.readTree(MINIMAL_JSON);
    }

    private static WorkflowDraft draft(long revision, String checksum) {
        return new WorkflowDraft(
                WORKFLOW_ID,
                PROJECT_ID,
                "service-test",
                "description",
                "DRAFT",
                revision,
                null,
                "{}",
                checksum,
                Instant.parse("2026-07-31T00:00:00Z"),
                Instant.parse("2026-07-31T00:00:00Z"));
    }
}
