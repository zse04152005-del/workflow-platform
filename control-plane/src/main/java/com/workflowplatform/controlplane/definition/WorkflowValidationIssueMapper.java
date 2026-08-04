package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import java.util.ArrayList;
import java.util.List;

/** Maps internal deterministic failures to the bounded public issue contract. */
final class WorkflowValidationIssueMapper {

    private static final String ERROR = "error";
    private static final String PARSE_MESSAGE = "Workflow definition could not be parsed.";
    private static final String NAME_MESSAGE =
            "Workflow definition name does not match the target workflow.";
    private static final String GRAPH_MESSAGE = "Workflow graph constraint was violated.";
    private static final String CONFIGURATION_MESSAGE =
            "Workflow node or trigger configuration is invalid.";
    private static final String EXPRESSION_MESSAGE = "Workflow expression could not be compiled.";
    private static final int POINTER_FIELD_SEGMENTS = 3;

    WorkflowValidationIssue parse(WorkflowDefinitionParseException exception) {
        return issue("DSL_PARSE_" + exception.failure().name(), "/", null, PARSE_MESSAGE);
    }

    WorkflowValidationIssue nameMismatch() {
        return issue("DSL_DEFINITION_NAME_MISMATCH", "/name", null, NAME_MESSAGE);
    }

    List<WorkflowValidationIssue> graph(
            WorkflowDefinition definition, List<WorkflowGraphViolation> violations) {
        List<WorkflowValidationIssue> issues = new ArrayList<>(violations.size());
        for (WorkflowGraphViolation violation : violations) {
            issues.add(
                    issue(
                            "DSL_GRAPH_" + violation.failure().name(),
                            violation.path(),
                            nodeId(definition, violation.path()),
                            GRAPH_MESSAGE));
        }
        return issues;
    }

    List<WorkflowValidationIssue> configuration(
            WorkflowDefinition definition, List<WorkflowConfigurationViolation> violations) {
        List<WorkflowValidationIssue> issues = new ArrayList<>(violations.size());
        for (WorkflowConfigurationViolation violation : violations) {
            issues.add(
                    issue(
                            "DSL_CONFIGURATION_" + violation.failure().name(),
                            violation.path(),
                            nodeId(definition, violation.path()),
                            CONFIGURATION_MESSAGE));
        }
        return issues;
    }

    List<WorkflowValidationIssue> expression(
            WorkflowDefinition definition, List<WorkflowExpressionViolation> violations) {
        List<WorkflowValidationIssue> issues = new ArrayList<>(violations.size());
        for (WorkflowExpressionViolation violation : violations) {
            issues.add(
                    issue(
                            "DSL_EXPRESSION_" + violation.failure().name(),
                            violation.path(),
                            nodeId(definition, violation.path()),
                            EXPRESSION_MESSAGE));
        }
        return issues;
    }

    private static WorkflowValidationIssue issue(
            String code, String path, String nodeId, String message) {
        return new WorkflowValidationIssue(code, path, nodeId, message, ERROR);
    }

    private static String nodeId(WorkflowDefinition definition, String path) {
        String[] segments = path.split("/", -1);
        if (segments.length < POINTER_FIELD_SEGMENTS) {
            return null;
        }
        Integer index = index(segments[2]);
        if (index == null) {
            return null;
        }
        if ("nodes".equals(segments[1]) && index < definition.nodes().size()) {
            return definition.nodes().get(index).id();
        }
        if (!"edges".equals(segments[1]) || index >= definition.edges().size()) {
            return null;
        }
        WorkflowEdge edge = definition.edges().get(index);
        if (segments.length > POINTER_FIELD_SEGMENTS && "from".equals(segments[3])) {
            return edge.from();
        }
        if (segments.length > POINTER_FIELD_SEGMENTS && "to".equals(segments[3])) {
            return edge.to();
        }
        return null;
    }

    private static Integer index(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
