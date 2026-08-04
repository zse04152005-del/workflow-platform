package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Extracts and explicitly rewrites only the resource-reference fields defined by DSL v1. */
final class WorkflowResourceReferences {

    private static final String CONNECTOR = "connector";
    private static final String SECRET = "secret";
    private static final Pattern REFERENCE =
            Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");
    private static final Comparator<WorkflowExportBundle.ResourceReference> REFERENCE_ORDER =
            Comparator.comparing(WorkflowExportBundle.ResourceReference::kind)
                    .thenComparing(WorkflowExportBundle.ResourceReference::reference);

    private WorkflowResourceReferences() {}

    static List<WorkflowExportBundle.ResourceReference> extract(JsonNode definition) {
        Set<WorkflowExportBundle.ResourceReference> references = new TreeSet<>(REFERENCE_ORDER);
        forEachConfiguration(
                definition,
                (kind, value) ->
                        references.add(new WorkflowExportBundle.ResourceReference(kind, value)));
        return List.copyOf(references);
    }

    static ObjectNode rewrite(
            JsonNode definition,
            String targetName,
            List<WorkflowPortabilityService.ReferenceMapping> mappings,
            boolean requireCompleteMapping) {
        if (!(definition instanceof ObjectNode source)) {
            throw invalid();
        }
        List<WorkflowExportBundle.ResourceReference> references = extract(definition);
        Map<String, String> replacements = mappings(mappings, references, requireCompleteMapping);
        ObjectNode rewritten = source.deepCopy();
        rewritten.put("name", targetName);
        rewriteConfigurations(rewritten, replacements);
        return rewritten;
    }

    static void requireManifest(
            JsonNode definition, List<WorkflowExportBundle.ResourceReference> declared) {
        if (declared == null || !extract(definition).equals(sorted(declared))) {
            throw invalid();
        }
    }

    private static Map<String, String> mappings(
            List<WorkflowPortabilityService.ReferenceMapping> supplied,
            List<WorkflowExportBundle.ResourceReference> references,
            boolean requireComplete) {
        List<WorkflowPortabilityService.ReferenceMapping> values =
                supplied == null ? List.of() : List.copyOf(supplied);
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> expected = new TreeSet<>();
        for (WorkflowExportBundle.ResourceReference reference : references) {
            expected.add(key(reference.kind(), reference.reference()));
        }
        for (WorkflowPortabilityService.ReferenceMapping mapping : values) {
            if (mapping == null
                    || !validKind(mapping.kind())
                    || !validReference(mapping.sourceReference())
                    || !validReference(mapping.targetReference())) {
                throw invalid();
            }
            String key = key(mapping.kind(), mapping.sourceReference());
            if (!expected.contains(key)
                    || result.putIfAbsent(key, mapping.targetReference()) != null) {
                throw invalid();
            }
        }
        if (requireComplete && result.size() != expected.size()) {
            throw invalid();
        }
        return Map.copyOf(result);
    }

    private static void rewriteConfigurations(ObjectNode definition, Map<String, String> mappings) {
        JsonNode triggers = definition.get("triggers");
        if (triggers instanceof ArrayNode array) {
            for (JsonNode trigger : array) {
                ObjectNode config = objectConfiguration(trigger);
                rewriteText(config, "hmacSecretRef", SECRET, mappings);
            }
        }
        JsonNode nodes = definition.get("nodes");
        if (nodes instanceof ArrayNode array) {
            for (JsonNode node : array) {
                ObjectNode config = objectConfiguration(node);
                rewriteText(config, "connectorRef", CONNECTOR, mappings);
                JsonNode secrets = config == null ? null : config.get("secretRefs");
                if (secrets instanceof ArrayNode secretArray) {
                    for (int index = 0; index < secretArray.size(); index++) {
                        JsonNode value = secretArray.get(index);
                        if (value.isTextual()) {
                            secretArray.set(
                                    index,
                                    secretArray.textNode(
                                            replacement(SECRET, value.textValue(), mappings)));
                        }
                    }
                }
            }
        }
    }

    private static void forEachConfiguration(JsonNode definition, ReferenceConsumer consumer) {
        JsonNode triggers = definition == null ? null : definition.get("triggers");
        if (triggers instanceof ArrayNode array) {
            for (JsonNode trigger : array) {
                readText(objectConfiguration(trigger), "hmacSecretRef", SECRET, consumer);
            }
        }
        JsonNode nodes = definition == null ? null : definition.get("nodes");
        if (nodes instanceof ArrayNode array) {
            for (JsonNode node : array) {
                ObjectNode config = objectConfiguration(node);
                readText(config, "connectorRef", CONNECTOR, consumer);
                JsonNode secrets = config == null ? null : config.get("secretRefs");
                if (secrets instanceof ArrayNode secretArray) {
                    for (JsonNode value : secretArray) {
                        if (value.isTextual()) {
                            consumer.accept(SECRET, value.textValue());
                        }
                    }
                }
            }
        }
    }

    private static ObjectNode objectConfiguration(JsonNode owner) {
        JsonNode config = owner == null ? null : owner.get("config");
        return config instanceof ObjectNode object ? object : null;
    }

    private static void readText(
            ObjectNode config, String field, String kind, ReferenceConsumer consumer) {
        JsonNode value = config == null ? null : config.get(field);
        if (value != null && value.isTextual()) {
            consumer.accept(kind, value.textValue());
        }
    }

    private static void rewriteText(
            ObjectNode config, String field, String kind, Map<String, String> mappings) {
        JsonNode value = config == null ? null : config.get(field);
        if (value != null && value.isTextual()) {
            config.put(field, replacement(kind, value.textValue(), mappings));
        }
    }

    private static String replacement(
            String kind, String source, Map<String, String> replacements) {
        return replacements.getOrDefault(key(kind, source), source);
    }

    private static List<WorkflowExportBundle.ResourceReference> sorted(
            List<WorkflowExportBundle.ResourceReference> values) {
        List<WorkflowExportBundle.ResourceReference> sorted = new ArrayList<>(values);
        if (sorted.stream()
                .anyMatch(
                        value ->
                                value == null
                                        || !validKind(value.kind())
                                        || !validReference(value.reference()))) {
            throw invalid();
        }
        sorted.sort(REFERENCE_ORDER);
        Set<WorkflowExportBundle.ResourceReference> unique = new TreeSet<>(REFERENCE_ORDER);
        unique.addAll(sorted);
        if (unique.size() != sorted.size()) {
            throw invalid();
        }
        return List.copyOf(sorted);
    }

    private static String key(String kind, String reference) {
        return kind + ':' + reference;
    }

    private static boolean validKind(String value) {
        return CONNECTOR.equals(value) || SECRET.equals(value);
    }

    private static boolean validReference(String value) {
        return value != null && value.length() <= 200 && REFERENCE.matcher(value).matches();
    }

    private static ApiProblemException invalid() {
        return ApiProblemException.of(ProblemCode.COMMON_VALIDATION_INVALID_FIELD);
    }

    @FunctionalInterface
    private interface ReferenceConsumer {
        void accept(String kind, String reference);
    }
}
