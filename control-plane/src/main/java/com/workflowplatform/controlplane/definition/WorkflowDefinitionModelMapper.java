package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.dsl.EdgeTransition;
import com.workflowplatform.contracts.dsl.NodeType;
import com.workflowplatform.contracts.dsl.ParameterMapping;
import com.workflowplatform.contracts.dsl.RetryPolicy;
import com.workflowplatform.contracts.dsl.RetryStrategy;
import com.workflowplatform.contracts.dsl.TriggerType;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import com.workflowplatform.contracts.dsl.WorkflowNode;
import com.workflowplatform.contracts.dsl.WorkflowParameters;
import com.workflowplatform.contracts.dsl.WorkflowTrigger;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class WorkflowDefinitionModelMapper {

    private static final String TYPE_FIELD = "type";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper mapper;

    WorkflowDefinitionModelMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    WorkflowDefinition map(ObjectNode root) {
        return new WorkflowDefinition(
                requiredInt(root, "schemaVersion"),
                requiredText(root, "name"),
                optionalText(root, "description"),
                requiredInt(root, "catalogVersion"),
                mapTriggers(requiredArray(root, "triggers")),
                mapNodes(requiredArray(root, "nodes")),
                mapEdges(requiredArray(root, "edges")),
                new WorkflowParameters(
                        optionalObjectMap(root, "inputSchema"),
                        optionalObjectMap(root, "outputSchema")),
                optionalObjectMap(root, "metadata"));
    }

    private List<WorkflowTrigger> mapTriggers(ArrayNode source) {
        List<WorkflowTrigger> values = new ArrayList<>(source.size());
        source.forEach(
                value -> {
                    ObjectNode trigger = requireObject(value, "trigger");
                    ObjectNode configuration = trigger.deepCopy();
                    configuration.remove(List.of("id", TYPE_FIELD));
                    values.add(
                            new WorkflowTrigger(
                                    requiredText(trigger, "id"),
                                    triggerType(requiredText(trigger, TYPE_FIELD)),
                                    toMap(configuration)));
                });
        return values;
    }

    private List<WorkflowNode> mapNodes(ArrayNode source) {
        List<WorkflowNode> values = new ArrayList<>(source.size());
        source.forEach(
                value -> {
                    ObjectNode node = requireObject(value, "node");
                    ObjectNode configuration = node.deepCopy();
                    configuration.remove(
                            List.of(
                                    "id",
                                    TYPE_FIELD,
                                    "executorVersion",
                                    "displayName",
                                    "timeout",
                                    "retry"));
                    values.add(
                            new WorkflowNode(
                                    requiredText(node, "id"),
                                    nodeType(requiredText(node, TYPE_FIELD)),
                                    requiredInt(node, "executorVersion"),
                                    optionalText(node, "displayName"),
                                    optionalDuration(node, "timeout"),
                                    optionalRetry(node),
                                    toMap(configuration)));
                });
        return values;
    }

    private List<WorkflowEdge> mapEdges(ArrayNode source) {
        List<WorkflowEdge> values = new ArrayList<>(source.size());
        source.forEach(
                value -> {
                    ObjectNode edge = requireObject(value, "edge");
                    values.add(
                            new WorkflowEdge(
                                    requiredText(edge, "id"),
                                    requiredText(edge, "from"),
                                    requiredText(edge, "to"),
                                    edgeTransition(requiredText(edge, "on")),
                                    optionalText(edge, "when"),
                                    mapParameterMappings(edge.get("mapping"))));
                });
        return values;
    }

    private List<ParameterMapping> mapParameterMappings(JsonNode source) {
        if (source == null) {
            return List.of();
        }
        ObjectNode mapping = requireObject(source, "mapping");
        List<ParameterMapping> values = new ArrayList<>(mapping.size());
        mapping.properties()
                .forEach(
                        entry ->
                                values.add(
                                        new ParameterMapping(
                                                entry.getKey(),
                                                requireText(
                                                        entry.getValue(), "mapping expression"))));
        return values;
    }

    private RetryPolicy optionalRetry(ObjectNode node) {
        JsonNode source = node.get("retry");
        if (source == null) {
            return null;
        }
        ObjectNode retry = requireObject(source, "retry");
        return new RetryPolicy(
                requiredInt(retry, "maxAttempts"),
                retryStrategy(requiredText(retry, "strategy")),
                requiredDuration(retry, "initialDelay"),
                optionalDuration(retry, "maxDelay"),
                optionalDuration(retry, "maxElapsedTime"),
                optionalDuration(retry, "increment"),
                optionalDecimal(retry, "multiplier"),
                optionalDecimal(retry, "jitterRatio"));
    }

    private Map<String, Object> optionalObjectMap(ObjectNode node, String field) {
        JsonNode source = node.get(field);
        return source == null ? null : toMap(requireObject(source, field));
    }

    private Map<String, Object> toMap(ObjectNode node) {
        return mapper.convertValue(node, JSON_OBJECT_TYPE);
    }

    private static String requiredText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return requireText(value, field);
    }

    private static String requireText(JsonNode value, String field) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }

    private static String optionalText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : requireText(value, field);
    }

    private static int requiredInt(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static ArrayNode requiredArray(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return array;
    }

    private static ObjectNode requireObject(JsonNode value, String label) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return object;
    }

    private static Duration requiredDuration(ObjectNode node, String field) {
        Duration value = optionalDuration(node, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Duration optionalDuration(ObjectNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 duration", exception);
        }
    }

    private static BigDecimal optionalDecimal(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return value.decimalValue();
    }

    private static NodeType nodeType(String value) {
        return switch (value) {
            case "start" -> NodeType.START;
            case "end" -> NodeType.END;
            case "http" -> NodeType.HTTP;
            case "condition" -> NodeType.CONDITION;
            case "delay" -> NodeType.DELAY;
            case "human_task" -> NodeType.HUMAN_TASK;
            default -> throw new IllegalArgumentException("Unknown node type: " + value);
        };
    }

    private static TriggerType triggerType(String value) {
        return switch (value) {
            case "manual" -> TriggerType.MANUAL;
            case "http" -> TriggerType.HTTP;
            case "cron" -> TriggerType.CRON;
            default -> throw new IllegalArgumentException("Unknown trigger type: " + value);
        };
    }

    private static EdgeTransition edgeTransition(String value) {
        return switch (value) {
            case "success" -> EdgeTransition.SUCCESS;
            case "failure" -> EdgeTransition.FAILURE;
            case "always" -> EdgeTransition.ALWAYS;
            case "condition" -> EdgeTransition.CONDITION;
            default -> throw new IllegalArgumentException("Unknown edge transition: " + value);
        };
    }

    private static RetryStrategy retryStrategy(String value) {
        return switch (value) {
            case "fixed" -> RetryStrategy.FIXED;
            case "linear" -> RetryStrategy.LINEAR;
            case "exponential" -> RetryStrategy.EXPONENTIAL;
            default -> throw new IllegalArgumentException("Unknown retry strategy: " + value);
        };
    }
}
