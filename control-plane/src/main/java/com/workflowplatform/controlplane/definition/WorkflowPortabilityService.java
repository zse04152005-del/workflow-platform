package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Application service for integrity-bound clone/import/export and bounded version differences. */
@Service
public final class WorkflowPortabilityService {

    static final int MAXIMUM_RESOURCE_MAPPINGS = 10_000;
    private static final Pattern CHECKSUM = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");
    private static final String CLONE_AUDIT = "workflow.definition.clone";
    private static final String IMPORT_AUDIT = "workflow.definition.import";

    private final WorkflowDefinitionParser parser;
    private final WorkflowPortabilityStore store;
    private final ObjectMapper objectMapper;
    private final WorkflowJsonDiff jsonDiff = new WorkflowJsonDiff();

    public WorkflowPortabilityService(
            WorkflowDefinitionParser definitionParser,
            WorkflowPortabilityStore portabilityStore,
            ObjectMapper jsonMapper) {
        parser = Objects.requireNonNull(definitionParser, "definitionParser");
        store = Objects.requireNonNull(portabilityStore, "portabilityStore");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public WorkflowDraft cloneWorkflow(
            WorkflowRequestContext context,
            UUID sourceWorkflowId,
            UUID targetProjectId,
            String name,
            String description,
            Integer sourceVersion,
            List<ReferenceMapping> resourceMappings,
            String idempotencyKey) {
        requireCreateRequest(context, targetProjectId, name, description, resourceMappings);
        Objects.requireNonNull(sourceWorkflowId, "sourceWorkflowId");
        requirePositiveVersion(sourceVersion);
        WorkflowDraftService.requireValidIdempotencyKey(idempotencyKey);
        WorkflowSnapshot source = store.load(context, sourceWorkflowId, sourceVersion);
        ParsedWorkflowDefinition parsedSource = parseStored(source);
        JsonNode sourceDefinition = tree(parsedSource.canonicalJson());
        boolean crossProject = !source.projectId().equals(targetProjectId);
        ObjectNode rewritten =
                WorkflowResourceReferences.rewrite(
                        sourceDefinition, name, resourceMappings, crossProject);
        ParsedWorkflowDefinition target = parseSupplied(rewritten);
        String effectiveDescription = description == null ? source.description() : description;
        return create(
                context,
                targetProjectId,
                name,
                effectiveDescription,
                target,
                SourceMetadata.from(source),
                CLONE_AUDIT,
                "workflow:clone:" + sourceWorkflowId,
                idempotencyKey);
    }

    public WorkflowExportBundle export(
            WorkflowRequestContext context, UUID workflowId, Integer version) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        requirePositiveVersion(version);
        WorkflowSnapshot snapshot = store.load(context, workflowId, version);
        ParsedWorkflowDefinition parsed = parseStored(snapshot);
        JsonNode definition = tree(parsed.canonicalJson());
        List<WorkflowExportBundle.ResourceReference> resources =
                WorkflowResourceReferences.extract(definition);
        requireResourceCount(resources.size());
        return new WorkflowExportBundle(
                WorkflowExportBundle.FORMAT,
                WorkflowExportBundle.FORMAT_VERSION,
                new WorkflowExportBundle.Source(
                        snapshot.workflowId(),
                        snapshot.sourceKind(),
                        snapshot.draftRevision(),
                        snapshot.version(),
                        snapshot.checksum(),
                        snapshot.capturedAt()),
                new WorkflowExportBundle.Workflow(
                        snapshot.name(), snapshot.description(), definition),
                resources);
    }

    public WorkflowDraft importWorkflow(
            WorkflowRequestContext context,
            UUID targetProjectId,
            String name,
            String description,
            WorkflowExportBundle bundle,
            List<ReferenceMapping> resourceMappings,
            String idempotencyKey) {
        requireCreateRequest(context, targetProjectId, name, description, resourceMappings);
        WorkflowDraftService.requireValidIdempotencyKey(idempotencyKey);
        SourceMetadata source = requireBundle(bundle);
        ParsedWorkflowDefinition parsedSource = parseSupplied(bundle.workflow().definition());
        if (!bundle.workflow().name().equals(parsedSource.definition().name())
                || !source.checksum().equals(parsedSource.checksum())) {
            throw invalid();
        }
        WorkflowResourceReferences.requireManifest(
                bundle.workflow().definition(), bundle.resources());
        ObjectNode rewritten =
                WorkflowResourceReferences.rewrite(
                        bundle.workflow().definition(), name, resourceMappings, true);
        ParsedWorkflowDefinition target = parseSupplied(rewritten);
        String effectiveDescription =
                description == null ? bundle.workflow().description() : description;
        WorkflowDraftService.requireMaximumLength(effectiveDescription, 1000);
        return create(
                context,
                targetProjectId,
                name,
                effectiveDescription,
                target,
                source,
                IMPORT_AUDIT,
                "workflow:import",
                idempotencyKey);
    }

    public WorkflowVersionDiff diff(
            WorkflowRequestContext context, UUID workflowId, int fromVersion, int toVersion) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        if (fromVersion < 1 || toVersion < 1) {
            throw invalid();
        }
        WorkflowSnapshot from = store.load(context, workflowId, fromVersion);
        WorkflowSnapshot to = store.load(context, workflowId, toVersion);
        ParsedWorkflowDefinition parsedFrom = parseStored(from);
        ParsedWorkflowDefinition parsedTo = parseStored(to);
        return jsonDiff.compare(
                from, tree(parsedFrom.canonicalJson()), to, tree(parsedTo.canonicalJson()));
    }

    private WorkflowDraft create(
            WorkflowRequestContext context,
            UUID targetProjectId,
            String name,
            String description,
            ParsedWorkflowDefinition target,
            SourceMetadata source,
            String auditAction,
            String operationScope,
            String idempotencyKey) {
        String keyDigest = WorkflowDraftService.digest("idempotency-key-v1", idempotencyKey);
        String requestDigest =
                WorkflowDraftService.digest(
                        auditAction,
                        targetProjectId.toString(),
                        name,
                        description == null ? "<null>" : description,
                        source.workflowId().toString(),
                        source.sourceKind(),
                        source.draftRevision() == null
                                ? "<null>"
                                : source.draftRevision().toString(),
                        source.version() == null ? "<null>" : source.version().toString(),
                        source.checksum(),
                        target.canonicalJson());
        return store.create(
                new WorkflowPortabilityStore.CreateCommand(
                        context,
                        TimeOrderedUuidV7.generate(),
                        targetProjectId,
                        name,
                        description,
                        target.canonicalJson(),
                        target.checksum(),
                        auditAction,
                        source.workflowId(),
                        source.sourceKind(),
                        source.draftRevision(),
                        source.version(),
                        source.checksum(),
                        traceId(),
                        new WorkflowDraftStore.IdempotentRequest(
                                TimeOrderedUuidV7.generate(),
                                operationScope,
                                keyDigest,
                                requestDigest)));
    }

    private SourceMetadata requireBundle(WorkflowExportBundle bundle) {
        if (bundle == null
                || !WorkflowExportBundle.FORMAT.equals(bundle.format())
                || bundle.formatVersion() != WorkflowExportBundle.FORMAT_VERSION
                || bundle.source() == null
                || bundle.workflow() == null
                || bundle.workflow().name() == null
                || bundle.workflow().definition() == null
                || bundle.resources() == null) {
            throw invalid();
        }
        requireResourceCount(bundle.resources().size());
        WorkflowExportBundle.Source source = bundle.source();
        if (source.workflowId() == null
                || source.capturedAt() == null
                || source.checksum() == null
                || !CHECKSUM.matcher(source.checksum()).matches()) {
            throw invalid();
        }
        boolean draftSelector =
                WorkflowSnapshot.DRAFT.equals(source.kind())
                        && source.draftRevision() != null
                        && source.draftRevision() >= 1
                        && source.version() == null;
        boolean publishedSelector =
                WorkflowSnapshot.PUBLISHED_VERSION.equals(source.kind())
                        && source.version() != null
                        && source.version() >= 1
                        && source.draftRevision() == null;
        if (!draftSelector && !publishedSelector) {
            throw invalid();
        }
        return new SourceMetadata(
                source.workflowId(),
                source.kind(),
                source.draftRevision(),
                source.version(),
                source.checksum(),
                source.capturedAt());
    }

    private ParsedWorkflowDefinition parseStored(WorkflowSnapshot snapshot) {
        ParsedWorkflowDefinition parsed;
        try {
            parsed =
                    parser.parse(
                            snapshot.serializedDefinition().getBytes(StandardCharsets.UTF_8),
                            WorkflowDefinitionSourceFormat.JSON);
        } catch (WorkflowDefinitionParseException exception) {
            throw new IllegalStateException("Persisted workflow snapshot is invalid", exception);
        }
        if (!snapshot.name().equals(parsed.definition().name())
                || !snapshot.checksum().equals(parsed.checksum())) {
            throw new IllegalStateException("Persisted workflow snapshot integrity check failed");
        }
        return parsed;
    }

    private ParsedWorkflowDefinition parseSupplied(JsonNode definition) {
        if (definition == null || definition.isNull()) {
            throw invalid();
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

    private JsonNode tree(String canonicalJson) {
        try {
            return objectMapper.readTree(canonicalJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Canonical workflow JSON is invalid", exception);
        }
    }

    private static void requireCreateRequest(
            WorkflowRequestContext context,
            UUID targetProjectId,
            String name,
            String description,
            List<ReferenceMapping> mappings) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(targetProjectId, "targetProjectId");
        WorkflowDraftService.requireValidName(name);
        WorkflowDraftService.requireMaximumLength(description, 1000);
        requireResourceCount(mappings == null ? 0 : mappings.size());
    }

    private static void requirePositiveVersion(Integer version) {
        if (version != null && version < 1) {
            throw invalid();
        }
    }

    private static void requireResourceCount(int count) {
        if (count > MAXIMUM_RESOURCE_MAPPINGS) {
            throw invalid();
        }
    }

    private static String traceId() {
        String value = TimeOrderedUuidV7.generate().toString().replace("-", "");
        if (!TRACE_ID.matcher(value).matches()) {
            throw new IllegalStateException("Generated trace identifier is invalid");
        }
        return value;
    }

    private static ApiProblemException invalid() {
        return ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
    }

    private record SourceMetadata(
            UUID workflowId,
            String sourceKind,
            Long draftRevision,
            Integer version,
            String checksum,
            Instant capturedAt) {

        private static SourceMetadata from(WorkflowSnapshot snapshot) {
            return new SourceMetadata(
                    snapshot.workflowId(),
                    snapshot.sourceKind(),
                    snapshot.draftRevision(),
                    snapshot.version(),
                    snapshot.checksum(),
                    snapshot.capturedAt());
        }
    }

    public record ReferenceMapping(String kind, String sourceReference, String targetReference) {}
}
