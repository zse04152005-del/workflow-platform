package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Application service for idempotent, revision-bound, transactionally revalidated publication. */
@Service
public final class WorkflowPublicationService {

    private static final int MAXIMUM_RELEASE_NOTE = 1000;
    private static final long MINIMUM_DRAFT_REVISION = 1;
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");

    private final WorkflowPublicationStore store;
    private final WorkflowValidationService validation;

    public WorkflowPublicationService(
            WorkflowPublicationStore publicationStore,
            WorkflowValidationService validationService) {
        store = Objects.requireNonNull(publicationStore, "publicationStore");
        validation = Objects.requireNonNull(validationService, "validationService");
    }

    public PublishedWorkflow publish(
            WorkflowRequestContext context,
            UUID workflowId,
            long expectedDraftRevision,
            String releaseNote,
            String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        if (expectedDraftRevision < MINIMUM_DRAFT_REVISION) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
        if (releaseNote != null
                && (releaseNote.isBlank() || releaseNote.length() > MAXIMUM_RELEASE_NOTE)) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
        WorkflowDraftService.requireValidIdempotencyKey(idempotencyKey);
        String keyDigest = WorkflowDraftService.digest("idempotency-key-v1", idempotencyKey);
        String requestDigest =
                WorkflowDraftService.digest(
                        "publish-workflow-v1",
                        workflowId.toString(),
                        Long.toString(expectedDraftRevision),
                        releaseNote == null ? "<null>" : releaseNote);
        String traceId = traceId();
        return store.publish(
                new WorkflowPublicationStore.PublishCommand(
                        context,
                        workflowId,
                        expectedDraftRevision,
                        releaseNote,
                        traceId,
                        new WorkflowPublicationStore.IdempotentRequest(
                                TimeOrderedUuidV7.generate(),
                                "workflow:publish:" + workflowId,
                                keyDigest,
                                requestDigest)),
                validation::prepareForPublication);
    }

    private static String traceId() {
        String value = TimeOrderedUuidV7.generate().toString().replace("-", "");
        if (!TRACE_ID.matcher(value).matches()) {
            throw new IllegalStateException("Generated trace identifier is invalid");
        }
        return value;
    }
}
