package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transactional tenant-scoped persistence port for draft reads and idempotent writes. */
public interface WorkflowDraftStore {

    WorkflowDraft create(CreateCommand command);

    Optional<WorkflowDraft> find(WorkflowRequestContext context, UUID workflowId);

    WorkflowDraft update(UpdateCommand command);

    record IdempotentRequest(
            UUID recordId, String operationScope, String keyDigest, String requestDigest) {

        public IdempotentRequest {
            Objects.requireNonNull(recordId, "recordId");
            operationScope = Objects.requireNonNull(operationScope, "operationScope");
            keyDigest = WorkflowDraftValidation.requireDigest(keyDigest, "keyDigest");
            requestDigest = WorkflowDraftValidation.requireDigest(requestDigest, "requestDigest");
        }
    }

    record CreateCommand(
            WorkflowRequestContext context,
            UUID workflowId,
            UUID projectId,
            String name,
            String description,
            String canonicalDefinition,
            String checksum,
            IdempotentRequest idempotency) {

        public CreateCommand {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(workflowId, "workflowId");
            Objects.requireNonNull(projectId, "projectId");
            name = Objects.requireNonNull(name, "name");
            canonicalDefinition =
                    Objects.requireNonNull(canonicalDefinition, "canonicalDefinition");
            checksum = WorkflowDraftValidation.requireDigest(checksum, "checksum");
            Objects.requireNonNull(idempotency, "idempotency");
        }
    }

    record UpdateCommand(
            WorkflowRequestContext context,
            UUID workflowId,
            StrongDraftEtag.RevisionToken expectedRevision,
            String definitionName,
            String canonicalDefinition,
            String checksum,
            IdempotentRequest idempotency) {

        public UpdateCommand {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(workflowId, "workflowId");
            Objects.requireNonNull(expectedRevision, "expectedRevision");
            definitionName = Objects.requireNonNull(definitionName, "definitionName");
            canonicalDefinition =
                    Objects.requireNonNull(canonicalDefinition, "canonicalDefinition");
            checksum = WorkflowDraftValidation.requireDigest(checksum, "checksum");
            Objects.requireNonNull(idempotency, "idempotency");
        }
    }
}
