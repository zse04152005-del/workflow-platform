package com.workflowplatform.controlplane.definition;

import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Tenant-scoped persistence port for clone/import creation and snapshot reads. */
public interface WorkflowPortabilityStore {

    Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");
    Set<String> AUDIT_ACTIONS = Set.of("workflow.definition.clone", "workflow.definition.import");

    WorkflowSnapshot load(
            WorkflowRequestContext context, UUID workflowId, Integer publishedVersion);

    WorkflowDraft create(CreateCommand command);

    record CreateCommand(
            WorkflowRequestContext context,
            UUID workflowId,
            UUID projectId,
            String name,
            String description,
            String canonicalDefinition,
            String checksum,
            String auditAction,
            UUID sourceWorkflowId,
            String sourceKind,
            Long sourceDraftRevision,
            Integer sourceVersion,
            String sourceChecksum,
            String traceId,
            WorkflowDraftStore.IdempotentRequest idempotency) {

        public CreateCommand {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(workflowId, "workflowId");
            Objects.requireNonNull(projectId, "projectId");
            name = Objects.requireNonNull(name, "name");
            canonicalDefinition =
                    Objects.requireNonNull(canonicalDefinition, "canonicalDefinition");
            checksum = WorkflowDraftValidation.requireDigest(checksum, "checksum");
            auditAction = Objects.requireNonNull(auditAction, "auditAction");
            if (!AUDIT_ACTIONS.contains(auditAction)) {
                throw new IllegalArgumentException("Unsupported portability audit action");
            }
            Objects.requireNonNull(sourceWorkflowId, "sourceWorkflowId");
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
            if (WorkflowSnapshot.DRAFT.equals(sourceKind)) {
                if (sourceDraftRevision == null
                        || sourceDraftRevision < 1
                        || sourceVersion != null) {
                    throw new IllegalArgumentException("Draft portability source is invalid");
                }
            } else if (WorkflowSnapshot.PUBLISHED_VERSION.equals(sourceKind)) {
                if (sourceVersion == null || sourceVersion < 1 || sourceDraftRevision != null) {
                    throw new IllegalArgumentException("Published portability source is invalid");
                }
            } else {
                throw new IllegalArgumentException("Unsupported portability source");
            }
            sourceChecksum =
                    WorkflowDraftValidation.requireDigest(sourceChecksum, "sourceChecksum");
            traceId = Objects.requireNonNull(traceId, "traceId");
            if (!TRACE_ID.matcher(traceId).matches()) {
                throw new IllegalArgumentException(
                        "traceId must be a non-zero 32-character digest");
            }
            Objects.requireNonNull(idempotency, "idempotency");
        }
    }
}
