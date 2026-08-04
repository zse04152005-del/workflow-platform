package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.contracts.dsl.EdgeTransition;
import com.workflowplatform.contracts.dsl.NodeType;
import com.workflowplatform.contracts.dsl.TriggerType;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import com.workflowplatform.contracts.dsl.WorkflowNode;
import com.workflowplatform.contracts.dsl.WorkflowParameters;
import com.workflowplatform.contracts.dsl.WorkflowTrigger;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** G3-10 deterministic property, compatibility and malicious-input regression suite. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class WorkflowDefinitionRobustnessTests {

    private static final long GENERATION_SEED = 30_310L;
    private static final int PARSER_PERMUTATION_CASES = 64;
    private static final int GENERATED_DAG_CASES = 48;
    private static final int GENERATED_CYCLE_CASES = 16;
    private static final String PAYLOAD_MARKER = "g3-10-never-echo-this-payload";
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000010");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000010");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowDefinitionParser parser = new JacksonWorkflowDefinitionParser();
    private final WorkflowGraphValidator graphValidator = new DeterministicWorkflowGraphValidator();
    private final WorkflowConfigurationValidator configurationValidator =
            new DeterministicWorkflowConfigurationValidator();
    private final WorkflowExpressionCompiler expressionCompiler =
            new DeterministicWorkflowExpressionCompiler();

    @Test
    void canonicalizationIsIdempotentAcrossSeededJsonPermutations() throws IOException {
        Random random = new Random(GENERATION_SEED);
        String canonical = null;
        String checksum = null;

        for (int index = 0; index < PARSER_PERMUTATION_CASES; index++) {
            ParsedWorkflowDefinition parsed =
                    parser.parse(permutedDefinition(random), WorkflowDefinitionSourceFormat.JSON);
            if (canonical == null) {
                canonical = parsed.canonicalJson();
                checksum = parsed.checksum();
            }
            assertEquals(canonical, parsed.canonicalJson());
            assertEquals(checksum, parsed.checksum());

            ParsedWorkflowDefinition reparsed =
                    parser.parse(parsed.canonicalBytes(), WorkflowDefinitionSourceFormat.JSON);
            assertEquals(parsed.canonicalJson(), reparsed.canonicalJson());
            assertEquals(parsed.checksum(), reparsed.checksum());
        }
    }

    @Test
    void combinedParserAttackCorpusIsRejectedWithoutPayloadEcho() {
        byte[] minimal = minimalJson().getBytes(StandardCharsets.UTF_8);
        List<ParserAttack> attacks =
                List.of(
                        attack(
                                doubleBom(minimal),
                                WorkflowDefinitionSourceFormat.JSON,
                                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT),
                        attack(
                                minimalJson()
                                        .replace(
                                                "\"catalogVersion\":1",
                                                "\"catalogVersion\":1,\"metadata\":{"
                                                        + "\"duplicate\":1,\"duplicate\":2}"),
                                WorkflowDefinitionSourceFormat.JSON,
                                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT),
                        attack(
                                minimalJson().replace("\"edges\":", "\u0000\"edges\":"),
                                WorkflowDefinitionSourceFormat.JSON,
                                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT),
                        attack(
                                minimalJson() + minimalJson(),
                                WorkflowDefinitionSourceFormat.JSON,
                                WorkflowDefinitionParseFailure.MULTIPLE_DOCUMENTS),
                        attack(
                                new byte[] {(byte) 0xF0, (byte) 0x28, (byte) 0x8C, (byte) 0x28},
                                WorkflowDefinitionSourceFormat.JSON,
                                WorkflowDefinitionParseFailure.INVALID_UTF8),
                        attack(
                                minimalYaml() + "extension: &shared [one]\ncopy: *shared\n",
                                WorkflowDefinitionSourceFormat.YAML,
                                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED),
                        attack(
                                minimalYaml()
                                        + "base: &base {safe: true}\nextension:\n  <<: *base\n",
                                WorkflowDefinitionSourceFormat.YAML,
                                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED),
                        attack(
                                minimalYaml() + "extension: !workflow/unsafe " + PAYLOAD_MARKER,
                                WorkflowDefinitionSourceFormat.YAML,
                                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT),
                        attack(
                                minimalJson()
                                        .replace(
                                                "\"edges\":",
                                                "\"extension\":"
                                                        + "[".repeat(65)
                                                        + "0"
                                                        + "]".repeat(65)
                                                        + ",\"edges\":"),
                                WorkflowDefinitionSourceFormat.JSON,
                                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED));

        for (ParserAttack attack : attacks) {
            WorkflowDefinitionParseException exception =
                    assertThrows(
                            WorkflowDefinitionParseException.class,
                            () -> parser.parse(attack.source(), attack.format()));
            assertEquals(attack.expected(), exception.failure());
            assertFalse(exception.getMessage().contains(PAYLOAD_MARKER));
        }
    }

    @Test
    void generatedLinearDagsValidateDeterministicallyAcrossSizes() {
        Random random = new Random(GENERATION_SEED);
        for (int index = 0; index < GENERATED_DAG_CASES; index++) {
            int nodeCount = index == 0 ? 500 : 2 + random.nextInt(499);
            WorkflowDefinition definition = linearDefinition(nodeCount);
            List<String> expected =
                    IntStream.range(0, nodeCount)
                            .mapToObj(value -> generatedNodeId(value, nodeCount))
                            .toList();

            ValidatedWorkflowGraph first = graphValidator.validate(definition);
            ValidatedWorkflowGraph second = graphValidator.validate(definition);
            assertEquals(expected, first.topologicalNodeIds());
            assertEquals(first.topologicalNodeIds(), second.topologicalNodeIds());
        }
    }

    @Test
    void generatedCycleMutationsAreRejectedDeterministically() {
        for (int index = 0; index < GENERATED_CYCLE_CASES; index++) {
            WorkflowDefinition definition = cyclicDefinition(5 + index);
            WorkflowGraphValidationException first =
                    assertThrows(
                            WorkflowGraphValidationException.class,
                            () -> graphValidator.validate(definition));
            WorkflowGraphValidationException second =
                    assertThrows(
                            WorkflowGraphValidationException.class,
                            () -> graphValidator.validate(definition));
            assertTrue(
                    first.violations().stream()
                            .anyMatch(
                                    violation ->
                                            violation.failure()
                                                    == WorkflowGraphValidationFailure
                                                            .CYCLE_NOT_ALLOWED));
            assertEquals(first.violations(), second.violations());
        }
    }

    @Test
    void frozenDslV1CompatibilityCorpusCompilesWithCanonicalChecksums() throws IOException {
        JsonNode contract =
                objectMapper.readTree(
                        Files.readString(
                                repositoryRoot().resolve("config/definition/robustness-suite.json"),
                                StandardCharsets.UTF_8));
        for (JsonNode fixture : contract.at("/compatibility/fixtures")) {
            Path path = repositoryRoot().resolve(fixture.get("path").textValue());
            ParsedWorkflowDefinition parsed =
                    parser.parse(Files.readAllBytes(path), WorkflowDefinitionSourceFormat.JSON);
            assertEquals(fixture.get("canonicalChecksum").textValue(), parsed.checksum());
            assertEquals(1, parsed.definition().schemaVersion());
            compile(parsed.definition());
        }
    }

    @Test
    void optionalRootExtensionsRemainPreservedWhileFutureMajorRequiresAnUpcaster()
            throws IOException {
        Path fixture =
                repositoryRoot()
                        .resolve(
                                "tests/contracts/dsl/robustness/compatibility/"
                                        + "v1-root-extension.json");
        ParsedWorkflowDefinition parsed =
                parser.parse(Files.readAllBytes(fixture), WorkflowDefinitionSourceFormat.JSON);
        assertTrue(parsed.canonicalJson().contains("\"x-vendor\""));
        compile(parsed.definition());

        byte[] future =
                Files.readString(fixture, StandardCharsets.UTF_8)
                        .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
                        .getBytes(StandardCharsets.UTF_8);
        WorkflowDefinitionParseException exception =
                assertThrows(
                        WorkflowDefinitionParseException.class,
                        () -> parser.parse(future, WorkflowDefinitionSourceFormat.JSON));
        assertEquals(WorkflowDefinitionParseFailure.CORE_MODEL_INVALID, exception.failure());
    }

    @Test
    void maliciousCelAndJsonPathCorporaCannotEscapeTheSandbox() {
        List<String> celAttacks =
                List.of(
                        "size(input.items) > 0",
                        "input.owner.size() > 0",
                        "Runtime.getRuntime() != null",
                        "System.getenv('HOME') != null",
                        "now == null",
                        "random > 0",
                        "new Object() == null",
                        "input.class == null",
                        "input.approved ? true : false",
                        "input.approved; true",
                        "[1, 2] == input.items",
                        "input.items.exists(x, x == 1)",
                        "Runtime." + PAYLOAD_MARKER.replace('-', '_') + " == null");
        for (String expression : celAttacks) {
            assertPayloadSafeExpressionRejection(conditionDefinition(expression));
        }

        List<String> pathAttacks =
                List.of(
                        "input..owner",
                        "input.*",
                        "input.items[*]",
                        "input.items[?(@.enabled)]",
                        "input.items[0:2]",
                        "input.items[0,1]",
                        "input.items[(@.length-1)]",
                        "$.input.owner",
                        "input['owner','approved']",
                        "input.items[-1]");
        for (String expression : pathAttacks) {
            assertPayloadSafeExpressionRejection(delayDefinition(expression));
        }
    }

    @Test
    void generatedExpressionBudgetBombsAreRejectedDeterministically() {
        List<WorkflowDefinition> bombs =
                List.of(
                        conditionDefinition("(".repeat(33) + "input.approved" + ")".repeat(33)),
                        conditionDefinition("input.approved" + " && input.approved".repeat(100)),
                        delayDefinition("response" + ".value".repeat(33)));
        for (WorkflowDefinition bomb : bombs) {
            WorkflowExpressionCompilationException exception =
                    assertThrows(WorkflowExpressionCompilationException.class, () -> compile(bomb));
            assertTrue(
                    exception.violations().stream()
                            .anyMatch(
                                    violation ->
                                            violation.failure()
                                                    == WorkflowExpressionCompilationFailure
                                                            .BUDGET_EXCEEDED));
        }
    }

    private byte[] permutedDefinition(Random random) throws IOException {
        List<Map<String, Object>> nodes =
                List.of(
                        shuffledMap(
                                random,
                                Map.of("id", "start", "type", "start", "executorVersion", 1)),
                        shuffledMap(
                                random, Map.of("id", "end", "type", "end", "executorVersion", 1)));
        List<Map<String, Object>> edges =
                List.of(
                        shuffledMap(
                                random,
                                Map.of(
                                        "id",
                                        "start_end",
                                        "from",
                                        "start",
                                        "to",
                                        "end",
                                        "on",
                                        "success")));
        BigDecimal equivalentNumber =
                new BigDecimal(List.of("1", "1.0", "1.00", "1E+0").get(random.nextInt(4)));
        Map<String, Object> extension =
                shuffledMap(
                        random,
                        Map.of(
                                "alpha",
                                equivalentNumber,
                                "beta",
                                new BigDecimal("-0.00"),
                                "values",
                                List.of(3, 2, 1)));
        Map<String, Object> root =
                shuffledMap(
                        random,
                        Map.of(
                                "schemaVersion",
                                1,
                                "name",
                                "g3-10-property",
                                "catalogVersion",
                                1,
                                "triggers",
                                List.of(Map.of("id", "manual", "type", "manual")),
                                "nodes",
                                nodes,
                                "edges",
                                edges,
                                "extension",
                                extension));
        return objectMapper.writeValueAsBytes(root);
    }

    private static Map<String, Object> shuffledMap(Random random, Map<String, Object> values) {
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.shuffle(keys, random);
        Map<String, Object> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, values.get(key)));
        return result;
    }

    private void assertPayloadSafeExpressionRejection(WorkflowDefinition definition) {
        WorkflowExpressionCompilationException exception =
                assertThrows(
                        WorkflowExpressionCompilationException.class, () -> compile(definition));
        assertFalse(exception.getMessage().contains(PAYLOAD_MARKER));
        assertTrue(exception.getMessage().startsWith("Workflow expressions rejected:"));
    }

    private CompiledWorkflowExpressions compile(WorkflowDefinition definition) {
        ValidatedWorkflowGraph graph = graphValidator.validate(definition);
        ValidatedWorkflowConfiguration configuration =
                configurationValidator.validate(graph, emptyContext());
        return expressionCompiler.compile(configuration);
    }

    private static WorkflowDefinition conditionDefinition(String expression) {
        List<WorkflowNode> nodes =
                List.of(
                        node("start", NodeType.START, Map.of()),
                        node(
                                "decision",
                                NodeType.CONDITION,
                                Map.of("config", Map.of("expression", expression))),
                        node("approved", NodeType.END, Map.of()),
                        node("fallback", NodeType.END, Map.of()));
        List<WorkflowEdge> edges =
                List.of(
                        edge("start_decision", "start", "decision", EdgeTransition.SUCCESS, null),
                        edge(
                                "decision_approved",
                                "decision",
                                "approved",
                                EdgeTransition.CONDITION,
                                "input.approved == true"),
                        edge(
                                "decision_fallback",
                                "decision",
                                "fallback",
                                EdgeTransition.ALWAYS,
                                null));
        return definition("g3-10-condition", nodes, edges, inputSchema());
    }

    private static WorkflowDefinition delayDefinition(String expression) {
        List<WorkflowNode> nodes =
                List.of(
                        node("start", NodeType.START, Map.of()),
                        node(
                                "wait",
                                NodeType.DELAY,
                                Map.of("config", Map.of("expiresAtExpression", expression))),
                        node("end", NodeType.END, Map.of()));
        List<WorkflowEdge> edges =
                List.of(
                        edge("start_wait", "start", "wait", EdgeTransition.SUCCESS, null),
                        edge("wait_end", "wait", "end", EdgeTransition.SUCCESS, null));
        return definition("g3-10-path", nodes, edges, inputSchema());
    }

    private static WorkflowDefinition linearDefinition(int nodeCount) {
        List<WorkflowNode> nodes =
                IntStream.range(0, nodeCount)
                        .mapToObj(
                                index ->
                                        node(
                                                generatedNodeId(index, nodeCount),
                                                index == 0
                                                        ? NodeType.START
                                                        : index == nodeCount - 1
                                                                ? NodeType.END
                                                                : NodeType.DELAY,
                                                Map.of()))
                        .toList();
        List<WorkflowEdge> edges =
                IntStream.range(0, nodeCount - 1)
                        .mapToObj(
                                index ->
                                        edge(
                                                "edge_" + index,
                                                generatedNodeId(index, nodeCount),
                                                generatedNodeId(index + 1, nodeCount),
                                                EdgeTransition.SUCCESS,
                                                null))
                        .toList();
        return definition("generated-dag", nodes, edges, null);
    }

    private static WorkflowDefinition cyclicDefinition(int nodeCount) {
        List<WorkflowNode> nodes =
                IntStream.range(0, nodeCount)
                        .mapToObj(
                                index ->
                                        node(
                                                generatedNodeId(index, nodeCount),
                                                index == 0
                                                        ? NodeType.START
                                                        : index == nodeCount - 1
                                                                ? NodeType.END
                                                                : NodeType.DELAY,
                                                Map.of()))
                        .toList();
        List<WorkflowEdge> edges = new ArrayList<>();
        for (int index = 0; index < nodeCount - 2; index++) {
            edges.add(
                    edge(
                            "edge_" + index,
                            generatedNodeId(index, nodeCount),
                            generatedNodeId(index + 1, nodeCount),
                            EdgeTransition.SUCCESS,
                            null));
        }
        String last = generatedNodeId(nodeCount - 2, nodeCount);
        edges.add(
                edge(
                        "cycle_back",
                        last,
                        generatedNodeId(1, nodeCount),
                        EdgeTransition.SUCCESS,
                        null));
        edges.add(edge("cycle_exit", last, "end", EdgeTransition.FAILURE, null));
        return definition("generated-cycle", nodes, edges, null);
    }

    private static WorkflowDefinition definition(
            String name,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            Map<String, Object> inputSchema) {
        return new WorkflowDefinition(
                1,
                name,
                null,
                1,
                List.of(new WorkflowTrigger("manual", TriggerType.MANUAL, Map.of())),
                nodes,
                edges,
                new WorkflowParameters(inputSchema, null),
                Map.of());
    }

    private static WorkflowNode node(String id, NodeType type, Map<String, Object> configuration) {
        return new WorkflowNode(id, type, 1, null, null, null, configuration);
    }

    private static WorkflowEdge edge(
            String id, String from, String to, EdgeTransition transition, String condition) {
        return new WorkflowEdge(id, from, to, transition, condition, List.of());
    }

    private static String generatedNodeId(int index, int nodeCount) {
        if (index == 0) {
            return "start";
        }
        return index == nodeCount - 1 ? "end" : "node_" + index;
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("approved", Map.of("type", "boolean"));
        properties.put("owner", Map.of("type", "string"));
        properties.put("items", Map.of("type", "array", "items", Map.of("type", "object")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static WorkflowConfigurationValidationContext emptyContext() {
        return new WorkflowConfigurationValidationContext(
                TENANT_ID, PROJECT_ID, List.of(), Set.of());
    }

    private static ParserAttack attack(
            String source,
            WorkflowDefinitionSourceFormat format,
            WorkflowDefinitionParseFailure expected) {
        return attack(source.getBytes(StandardCharsets.UTF_8), format, expected);
    }

    private static ParserAttack attack(
            byte[] source,
            WorkflowDefinitionSourceFormat format,
            WorkflowDefinitionParseFailure expected) {
        return new ParserAttack(source.clone(), format, expected);
    }

    private static byte[] doubleBom(byte[] source) {
        byte[] value = new byte[source.length + 6];
        value[0] = (byte) 0xEF;
        value[1] = (byte) 0xBB;
        value[2] = (byte) 0xBF;
        value[3] = (byte) 0xEF;
        value[4] = (byte) 0xBB;
        value[5] = (byte) 0xBF;
        System.arraycopy(source, 0, value, 6, source.length);
        return value;
    }

    private static String minimalJson() {
        return """
                {"schemaVersion":1,"name":"g3-10-parser","catalogVersion":1,
                "triggers":[{"id":"manual","type":"manual"}],
                "nodes":[{"id":"start","type":"start","executorVersion":1},
                {"id":"end","type":"end","executorVersion":1}],
                "edges":[{"id":"start_end","from":"start","to":"end","on":"success"}]}
                """;
    }

    private static String minimalYaml() {
        return """
                schemaVersion: 1
                name: g3-10-parser
                catalogVersion: 1
                triggers: [{id: manual, type: manual}]
                nodes:
                  - {id: start, type: start, executorVersion: 1}
                  - {id: end, type: end, executorVersion: 1}
                edges: [{id: start_end, from: start, to: end, on: success}]
                """;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("tests/contracts/dsl"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("tests/contracts/dsl"))) {
            return parent;
        }
        throw new IllegalStateException("Cannot locate repository root from " + current);
    }

    private record ParserAttack(
            byte[] source,
            WorkflowDefinitionSourceFormat format,
            WorkflowDefinitionParseFailure expected) {

        private ParserAttack {
            source = source.clone();
        }

        @Override
        public byte[] source() {
            return source.clone();
        }
    }
}
