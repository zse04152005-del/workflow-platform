package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.LengthPrefixedSha256;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Application service for bounded draft parsing, reads and idempotent optimistic writes. */
@Service
public final class WorkflowDraftService {

    private static final Pattern WORKFLOW_NAME = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._~-]{8,128}$");
    private static final int MAXIMUM_WORKFLOW_NAME = 120;
    private static final int MAXIMUM_DESCRIPTION = 1000;
    private static final int MAXIMUM_CHANGE_NOTE = 500;

    private final WorkflowDefinitionParser parser;
    private final WorkflowDraftStore store;
    private final ObjectMapper objectMapper;

    public WorkflowDraftService(
            WorkflowDefinitionParser definitionParser,
            WorkflowDraftStore draftStore,
            ObjectMapper jsonMapper) {
        parser = Objects.requireNonNull(definitionParser, "definitionParser");
        store = Objects.requireNonNull(draftStore, "draftStore");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public WorkflowDraft create(
            WorkflowRequestContext context,
            UUID projectId,
            String name,
            String description,
            JsonNode definition,
            String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(projectId, "projectId");
        requireValidName(name);
        requireMaximumLength(description, MAXIMUM_DESCRIPTION);
        String keyDigest = idempotencyKeyDigest(idempotencyKey);
        ParsedWorkflowDefinition parsed = parse(definition);
        if (!name.equals(parsed.definition().name())) {
            throw new WorkflowDraftOperationException(
                    WorkflowDraftOperationException.Failure.DEFINITION_NAME_MISMATCH);
        }
        String requestDigest =
                digest(
                        "create-workflow-v1",
                        projectId.toString(),
                        name,
                        nullable(description),
                        parsed.canonicalJson());
        return store.create(
                new WorkflowDraftStore.CreateCommand(
                        context,
                        TimeOrderedUuidV7.generate(),
                        projectId,
                        name,
                        description,
                        parsed.canonicalJson(),
                        parsed.checksum(),
                        idempotency("workflow:create", keyDigest, requestDigest)));
    }

    public WorkflowDraft get(WorkflowRequestContext context, UUID workflowId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        return store.find(context, workflowId)
                .orElseThrow(
                        () ->
                                new WorkflowDraftOperationException(
                                        WorkflowDraftOperationException.Failure.NOT_FOUND));
    }

    public WorkflowDraft update(
            WorkflowRequestContext context,
            UUID workflowId,
            JsonNode definition,
            String changeNote,
            String idempotencyKey,
            String ifMatch) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        requireMaximumLength(changeNote, MAXIMUM_CHANGE_NOTE);
        String keyDigest = idempotencyKeyDigest(idempotencyKey);
        StrongDraftEtag.RevisionToken expected = StrongDraftEtag.parseIfMatch(ifMatch);
        if (!workflowId.equals(expected.workflowId())) {
            throw new WorkflowDraftOperationException(
                    WorkflowDraftOperationException.Failure.PRECONDITION_FAILED);
        }
        ParsedWorkflowDefinition parsed = parse(definition);
        String requestDigest =
                digest(
                        "update-workflow-draft-v1",
                        workflowId.toString(),
                        StrongDraftEtag.format(expected),
                        parsed.canonicalJson(),
                        nullable(changeNote));
        return store.update(
                new WorkflowDraftStore.UpdateCommand(
                        context,
                        workflowId,
                        expected,
                        parsed.definition().name(),
                        parsed.canonicalJson(),
                        parsed.checksum(),
                        idempotency(
                                "workflow:draft:update:" + workflowId, keyDigest, requestDigest)));
    }

    private ParsedWorkflowDefinition parse(JsonNode definition) {
        if (definition == null || definition.isNull()) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
        try {
            return parser.parse(
                    objectMapper.writeValueAsBytes(definition),
                    WorkflowDefinitionSourceFormat.JSON);
        } catch (WorkflowDefinitionParseException | JsonProcessingException exception) {
            throw ApiProblemException.causedBy(
                    ProblemCode.COMMON_VALIDATION_INVALID_FIELD, exception);
        }
    }

    private static WorkflowDraftStore.IdempotentRequest idempotency(
            String scope, String keyDigest, String requestDigest) {
        return new WorkflowDraftStore.IdempotentRequest(
                TimeOrderedUuidV7.generate(), scope, keyDigest, requestDigest);
    }

    private static String idempotencyKeyDigest(String key) {
        requireValidIdempotencyKey(key);
        return digest("idempotency-key-v1", key);
    }

    static void requireValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_MISSING_PARAMETER);
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
    }

    static void requireValidName(String name) {
        if (name == null
                || name.length() > MAXIMUM_WORKFLOW_NAME
                || !WORKFLOW_NAME.matcher(name).matches()) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
    }

    static void requireMaximumLength(String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
        }
    }

    private static String nullable(String value) {
        return value == null ? "<null>" : value;
    }

    static String digest(String... values) {
        return LengthPrefixedSha256.digest(values);
    }
}
