package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class WorkflowPublicationServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "publication-service-actor");

    private WorkflowPublicationStore store;
    private WorkflowValidationService validation;
    private WorkflowPublicationService service;

    @BeforeEach
    void setUp() {
        store = mock(WorkflowPublicationStore.class);
        validation = mock(WorkflowValidationService.class);
        service = new WorkflowPublicationService(store, validation);
    }

    @Test
    void bindsTheRequestAndIdempotencyKeyToThePublicationCommand() {
        PublishedWorkflow expected =
                new PublishedWorkflow(WORKFLOW_ID, 1, "a".repeat(64), Instant.EPOCH);
        when(store.publish(any(), any())).thenReturn(expected);

        assertEquals(
                expected,
                service.publish(CONTEXT, WORKFLOW_ID, 4, "release candidate", "publish-key-101"));

        ArgumentCaptor<WorkflowPublicationStore.PublishCommand> command =
                ArgumentCaptor.forClass(WorkflowPublicationStore.PublishCommand.class);
        ArgumentCaptor<WorkflowPublicationStore.PublicationPreparer> preparer =
                ArgumentCaptor.forClass(WorkflowPublicationStore.PublicationPreparer.class);
        verify(store).publish(command.capture(), preparer.capture());
        assertEquals(CONTEXT, command.getValue().context());
        assertEquals(WORKFLOW_ID, command.getValue().workflowId());
        assertEquals(4, command.getValue().expectedDraftRevision());
        assertEquals("release candidate", command.getValue().releaseNote());
        assertEquals(
                "workflow:publish:" + WORKFLOW_ID,
                command.getValue().idempotency().operationScope());
        assertEquals(64, command.getValue().idempotency().keyDigest().length());
        assertEquals(64, command.getValue().idempotency().requestDigest().length());
        assertTrue(command.getValue().traceId().matches("^(?!0{32}$)[0-9a-f]{32}$"));
    }

    @Test
    void requestDigestChangesWhenTheRevisionOrReleaseNoteChanges() {
        when(store.publish(any(), any()))
                .thenReturn(new PublishedWorkflow(WORKFLOW_ID, 1, "b".repeat(64), Instant.EPOCH));
        service.publish(CONTEXT, WORKFLOW_ID, 1, null, "publish-key-102");
        service.publish(CONTEXT, WORKFLOW_ID, 2, "second", "publish-key-102");

        ArgumentCaptor<WorkflowPublicationStore.PublishCommand> commands =
                ArgumentCaptor.forClass(WorkflowPublicationStore.PublishCommand.class);
        verify(store, org.mockito.Mockito.times(2)).publish(commands.capture(), any());
        assertEquals(
                commands.getAllValues().get(0).idempotency().keyDigest(),
                commands.getAllValues().get(1).idempotency().keyDigest());
        assertNotEquals(
                commands.getAllValues().get(0).idempotency().requestDigest(),
                commands.getAllValues().get(1).idempotency().requestDigest());
    }

    @Test
    void rejectsInvalidRevisionReleaseNoteAndIdempotencyKeyBeforePersistence() {
        ApiProblemException revision =
                assertThrows(
                        ApiProblemException.class,
                        () -> service.publish(CONTEXT, WORKFLOW_ID, 0, null, "publish-key-103"));
        assertEquals("COMMON_VALIDATION_INVALID_FIELD", revision.problemCode().name());
        assertThrows(
                ApiProblemException.class,
                () -> service.publish(CONTEXT, WORKFLOW_ID, 1, "   ", "publish-key-104"));
        assertThrows(
                ApiProblemException.class,
                () -> service.publish(CONTEXT, WORKFLOW_ID, 1, null, "short"));
    }
}
