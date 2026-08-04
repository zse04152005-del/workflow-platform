package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;

/** Atomic tenant-scoped persistence port for immutable workflow publication. */
@FunctionalInterface
public interface WorkflowPublicationStore {

    long MINIMUM_DRAFT_REVISION = 1;

    PublishedWorkflow publish(PublishCommand command, PublicationPreparer preparer);

    @FunctionalInterface
    interface PublicationPreparer {
        WorkflowPublicationCandidate prepare(
                WorkflowRequestContext context, WorkflowDraft lockedDraft);
    }

    record IdempotentRequest(
            UUID recordId, String operationScope, String keyDigest, String requestDigest) {

        public IdempotentRequest {
            Objects.requireNonNull(recordId, "recordId");
            operationScope = Objects.requireNonNull(operationScope, "operationScope");
            keyDigest = WorkflowDraftValidation.requireDigest(keyDigest, "keyDigest");
            requestDigest = WorkflowDraftValidation.requireDigest(requestDigest, "requestDigest");
        }
    }

    record PublishCommand(
            WorkflowRequestContext context,
            UUID workflowId,
            long expectedDraftRevision,
            String releaseNote,
            String traceId,
            IdempotentRequest idempotency) {

        public PublishCommand {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(workflowId, "workflowId");
            traceId = Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(idempotency, "idempotency");
            if (expectedDraftRevision < MINIMUM_DRAFT_REVISION) {
                throw new IllegalArgumentException("expectedDraftRevision must be positive");
            }
        }
    }
}
