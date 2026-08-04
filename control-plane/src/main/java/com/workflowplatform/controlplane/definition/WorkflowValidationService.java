package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Runs the deterministic parse, graph, resource/configuration and expression pipeline. */
@Service
public final class WorkflowValidationService {

    static final int MAXIMUM_PUBLIC_ISSUES = 500;
    private static final String CURRENT_DRAFT = "current_draft";
    private static final String SUPPLIED_DEFINITION = "supplied_definition";

    private final WorkflowDraftService drafts;
    private final WorkflowDefinitionParser parser;
    private final WorkflowGraphValidator graphValidator;
    private final WorkflowValidationContextStore contextStore;
    private final WorkflowConfigurationValidator configurationValidator;
    private final WorkflowExpressionCompiler expressionCompiler;
    private final ObjectMapper objectMapper;
    private final WorkflowValidationIssueMapper issueMapper = new WorkflowValidationIssueMapper();

    public WorkflowValidationService(
            WorkflowDraftService draftService,
            WorkflowDefinitionParser definitionParser,
            WorkflowGraphValidator workflowGraphValidator,
            WorkflowValidationContextStore validationContextStore,
            WorkflowConfigurationValidator workflowConfigurationValidator,
            WorkflowExpressionCompiler workflowExpressionCompiler,
            ObjectMapper jsonMapper) {
        drafts = Objects.requireNonNull(draftService, "draftService");
        parser = Objects.requireNonNull(definitionParser, "definitionParser");
        graphValidator = Objects.requireNonNull(workflowGraphValidator, "workflowGraphValidator");
        contextStore = Objects.requireNonNull(validationContextStore, "validationContextStore");
        configurationValidator =
                Objects.requireNonNull(
                        workflowConfigurationValidator, "workflowConfigurationValidator");
        expressionCompiler =
                Objects.requireNonNull(workflowExpressionCompiler, "workflowExpressionCompiler");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public WorkflowValidationResult validate(
            WorkflowRequestContext context,
            UUID workflowId,
            JsonNode suppliedDefinition,
            String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(workflowId, "workflowId");
        WorkflowDraftService.requireValidIdempotencyKey(idempotencyKey);
        WorkflowDraft draft = drafts.get(context, workflowId);
        boolean supplied = suppliedDefinition != null;
        String source = supplied ? SUPPLIED_DEFINITION : CURRENT_DRAFT;
        Long revision = supplied ? null : draft.draftRevision();

        return evaluate(context, draft, suppliedDefinition, supplied, source, revision, false)
                .result();
    }

    WorkflowPublicationCandidate prepareForPublication(
            WorkflowRequestContext context, WorkflowDraft lockedDraft) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(lockedDraft, "lockedDraft");
        ValidationExecution execution =
                evaluate(
                        context,
                        lockedDraft,
                        null,
                        false,
                        CURRENT_DRAFT,
                        lockedDraft.draftRevision(),
                        true);
        if (!execution.result().valid() || execution.candidate() == null) {
            throw new WorkflowPublicationException(
                    WorkflowPublicationException.Failure.VALIDATION_FAILED);
        }
        return execution.candidate();
    }

    private ValidationExecution evaluate(
            WorkflowRequestContext context,
            WorkflowDraft draft,
            JsonNode suppliedDefinition,
            boolean supplied,
            String source,
            Long revision,
            boolean createCandidate) {

        ParsedWorkflowDefinition parsed;
        try {
            parsed = parse(draft, suppliedDefinition, supplied);
        } catch (WorkflowDefinitionParseException exception) {
            if (!supplied) {
                throw new IllegalStateException("Persisted workflow draft is invalid", exception);
            }
            return failure(source, revision, null, List.of(issueMapper.parse(exception)));
        }

        if (!draft.name().equals(parsed.definition().name())) {
            return failure(
                    source, revision, parsed.checksum(), List.of(issueMapper.nameMismatch()));
        }

        ValidatedWorkflowGraph graph;
        try {
            graph = graphValidator.validate(parsed.definition());
        } catch (WorkflowGraphValidationException exception) {
            return failure(
                    source,
                    revision,
                    parsed.checksum(),
                    issueMapper.graph(parsed.definition(), exception.violations()));
        }

        ValidatedWorkflowConfiguration configuration;
        try {
            WorkflowConfigurationValidationContext validationContext =
                    contextStore.load(context, draft.projectId());
            configuration = configurationValidator.validate(graph, validationContext);
        } catch (WorkflowConfigurationValidationException exception) {
            return failure(
                    source,
                    revision,
                    parsed.checksum(),
                    issueMapper.configuration(parsed.definition(), exception.violations()));
        }

        try {
            expressionCompiler.compile(configuration);
        } catch (WorkflowExpressionCompilationException exception) {
            return failure(
                    source,
                    revision,
                    parsed.checksum(),
                    issueMapper.expression(parsed.definition(), exception.violations()));
        }
        WorkflowValidationResult success = result(source, revision, parsed.checksum(), List.of());
        WorkflowPublicationCandidate candidate =
                createCandidate
                        ? new WorkflowPublicationCandidate(draft, parsed, configuration)
                        : null;
        return new ValidationExecution(success, candidate);
    }

    private ParsedWorkflowDefinition parse(
            WorkflowDraft draft, JsonNode suppliedDefinition, boolean supplied) {
        if (!supplied) {
            return parser.parse(
                    draft.canonicalDefinition().getBytes(StandardCharsets.UTF_8),
                    WorkflowDefinitionSourceFormat.JSON);
        }
        try {
            return parser.parse(
                    objectMapper.writeValueAsBytes(suppliedDefinition),
                    WorkflowDefinitionSourceFormat.JSON);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON tree could not be serialized", exception);
        }
    }

    private static WorkflowValidationResult result(
            String source,
            Long revision,
            String checksum,
            List<WorkflowValidationIssue> allIssues) {
        int count = allIssues.size();
        List<WorkflowValidationIssue> visible =
                count > MAXIMUM_PUBLIC_ISSUES
                        ? List.copyOf(allIssues.subList(0, MAXIMUM_PUBLIC_ISSUES))
                        : List.copyOf(allIssues);
        return new WorkflowValidationResult(
                source, revision, checksum, count == 0, count, count > visible.size(), visible);
    }

    private static ValidationExecution failure(
            String source, Long revision, String checksum, List<WorkflowValidationIssue> issues) {
        return new ValidationExecution(result(source, revision, checksum, issues), null);
    }

    private record ValidationExecution(
            WorkflowValidationResult result, WorkflowPublicationCandidate candidate) {}
}
