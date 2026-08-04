package com.workflowplatform.contracts.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowDslCoreModelTests {

    private static final String EDGE_ID = "edge";
    private static final String END_ID = "end";
    private static final String MANUAL_ID = "manual";
    private static final String START_ID = "start";
    private static final String VALID_ID = "valid";
    private static final String VALID_NAME = "valid-name";
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    @Test
    void exposesTheFrozenDslV1Discriminators() {
        assertEquals(1, WorkflowDefinition.CURRENT_SCHEMA_VERSION, "DSL schema version must be 1");
        assertEquals(
                List.of(START_ID, END_ID, "http", "condition", "delay", "human_task"),
                List.of(NodeType.values()).stream().map(NodeType::wireName).toList(),
                "Node wire values must remain stable");
        assertEquals(
                List.of(MANUAL_ID, "http", "cron"),
                List.of(TriggerType.values()).stream().map(TriggerType::wireName).toList(),
                "Trigger wire values must remain stable");
        assertEquals(
                List.of("success", "failure", "always", "condition"),
                List.of(EdgeTransition.values()).stream().map(EdgeTransition::wireName).toList(),
                "Edge transition wire values must remain stable");
        assertEquals(
                List.of("fixed", "linear", "exponential"),
                List.of(RetryStrategy.values()).stream().map(RetryStrategy::wireName).toList(),
                "Retry strategy wire values must remain stable");
    }

    @Test
    void buildsAnImmutableWorkflowDefinition() {
        List<Object> required = new ArrayList<>(List.of("amount"));
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("required", required);
        Map<String, Object> nodeConfiguration = new LinkedHashMap<>();
        nodeConfiguration.put("connectorRef", "payment.refund");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("owner", "payments");
        List<WorkflowNode> nodes =
                new ArrayList<>(
                        List.of(
                                startNode(),
                                new WorkflowNode(
                                        "refund_api",
                                        NodeType.HTTP,
                                        2,
                                        "Refund API",
                                        TEN_SECONDS,
                                        linearRetry(),
                                        nodeConfiguration),
                                endNode()));

        WorkflowDefinition definition =
                new WorkflowDefinition(
                        1,
                        "refund-approval",
                        "Refund workflow",
                        3,
                        List.of(new WorkflowTrigger(MANUAL_ID, TriggerType.MANUAL, Map.of())),
                        nodes,
                        List.of(
                                successEdge("start_to_refund", START_ID, "refund_api"),
                                successEdge("refund_to_end", "refund_api", END_ID)),
                        new WorkflowParameters(inputSchema, Map.of("type", "object")),
                        metadata);

        required.add("currency");
        inputSchema.put("title", "changed");
        nodeConfiguration.put("method", "DELETE");
        nodes.clear();
        metadata.put("changed", true);

        assertEquals(3, definition.nodes().size(), "The copied node list must remain unchanged");
        assertEquals(
                "payments",
                definition.metadata().get("owner"),
                "The copied metadata must remain unchanged");
        assertEquals(
                List.of("amount"),
                definition.parameters().inputSchema().get("required"),
                "The nested input schema list must remain unchanged");
        assertEquals(
                Map.of("connectorRef", "payment.refund"),
                definition.nodes().get(1).configuration(),
                "The copied node configuration must remain unchanged");
        assertThrows(
                UnsupportedOperationException.class, () -> definition.nodes().add(startNode()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> definition.parameters().inputSchema().put("type", "array"));
        @SuppressWarnings("unchecked")
        List<Object> copiedRequired =
                (List<Object>) definition.parameters().inputSchema().get("required");
        assertThrows(UnsupportedOperationException.class, () -> copiedRequired.add("currency"));
    }

    @Test
    void rejectsInvalidWorkflowRootFieldsAndCollectionBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                2, VALID_NAME, 1, validTriggers(), validNodes(), validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                1, "Invalid_Name", 1, validTriggers(), validNodes(), validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                1, VALID_NAME, 0, validTriggers(), validNodes(), validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () -> validDefinition(1, VALID_NAME, 1, List.of(), validNodes(), validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                1,
                                VALID_NAME,
                                1,
                                validTriggers(),
                                List.of(startNode()),
                                validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () -> validDefinition(1, VALID_NAME, 1, validTriggers(), validNodes(), List.of()));
        assertThrows(
                NullPointerException.class,
                () -> validDefinition(1, VALID_NAME, 1, null, validNodes(), validEdges()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new WorkflowDefinition(
                                1,
                                VALID_NAME,
                                null,
                                1,
                                validTriggers(),
                                validNodes(),
                                validEdges(),
                                null,
                                Map.of()));

        List<WorkflowTrigger> tooManyTriggers = Collections.nCopies(21, validTriggers().getFirst());
        List<WorkflowNode> tooManyNodes = Collections.nCopies(501, startNode());
        List<WorkflowEdge> tooManyEdges = Collections.nCopies(2001, validEdges().getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                1, VALID_NAME, 1, tooManyTriggers, validNodes(), validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                1, VALID_NAME, 1, validTriggers(), tooManyNodes, validEdges()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validDefinition(
                                1, VALID_NAME, 1, validTriggers(), validNodes(), tooManyEdges));
    }

    @Test
    void validatesDescriptionsMetadataAndJsonValues() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowDefinition(
                                1,
                                VALID_NAME,
                                "x".repeat(1001),
                                1,
                                validTriggers(),
                                validNodes(),
                                validEdges(),
                                emptyParameters(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> definitionWithMetadata(Map.of("Invalid-Key", "value")));
        Map<String, Object> nestedMetadata = Map.of("owner", Map.of("team", "payments"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithMetadata(nestedMetadata));
        Map<String, Object> tooMuchMetadata = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) {
            tooMuchMetadata.put("key_" + index, index);
        }
        assertThrows(IllegalArgumentException.class, () -> definitionWithMetadata(tooMuchMetadata));

        Map<Object, Object> nonStringKeys = new HashMap<>();
        nonStringKeys.put(42, "value");
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowParameters(Map.of("nested", nonStringKeys), Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowParameters(
                                Map.of("invalid", new StringBuilder("value")), Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowParameters(Map.of("invalid", Double.NaN), Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowParameters(Map.of("invalid", Float.POSITIVE_INFINITY), Map.of()));
    }

    @Test
    void validatesNodeCommonFieldsAndHttpOnlyPolicies() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowNode("Bad-Id", NodeType.START, 1, null, null, null, Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new WorkflowNode(VALID_ID, null, 1, null, null, null, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowNode(VALID_ID, NodeType.START, 0, null, null, null, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowNode(
                                VALID_ID,
                                NodeType.START,
                                1,
                                "x".repeat(121),
                                null,
                                null,
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowNode(
                                VALID_ID, NodeType.HTTP, 1, null, Duration.ZERO, null, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowNode(
                                VALID_ID, NodeType.DELAY, 1, null, ONE_SECOND, null, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowNode(
                                VALID_ID,
                                NodeType.CONDITION,
                                1,
                                null,
                                null,
                                fixedRetry(),
                                Map.of()));
    }

    @Test
    void supportsFixedLinearAndExponentialRetryPolicies() {
        RetryPolicy fixed = fixedRetry();
        RetryPolicy linear = linearRetry();
        RetryPolicy exponential =
                new RetryPolicy(
                        5,
                        RetryStrategy.EXPONENTIAL,
                        ONE_SECOND,
                        TEN_SECONDS,
                        Duration.ofMinutes(1),
                        null,
                        BigDecimal.valueOf(2),
                        BigDecimal.ZERO);

        assertEquals(
                RetryStrategy.FIXED, fixed.strategy(), "Fixed retry must preserve its strategy");
        assertEquals(
                Duration.ofSeconds(2),
                linear.increment(),
                "Linear retry must preserve its increment");
        assertEquals(
                BigDecimal.valueOf(2),
                exponential.multiplier(),
                "Exponential retry must preserve its multiplier");
    }

    @Test
    void rejectsInvalidRetryBoundsAndDurations() {
        assertThrows(IllegalArgumentException.class, () -> fixedRetry(0));
        assertThrows(IllegalArgumentException.class, () -> fixedRetry(11));
        assertThrows(
                NullPointerException.class,
                () -> new RetryPolicy(1, null, ONE_SECOND, null, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.FIXED,
                                Duration.ofSeconds(-1),
                                null,
                                null,
                                null,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.FIXED,
                                TEN_SECONDS,
                                ONE_SECOND,
                                null,
                                null,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.FIXED,
                                TEN_SECONDS,
                                null,
                                ONE_SECOND,
                                null,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.EXPONENTIAL,
                                ONE_SECOND,
                                null,
                                null,
                                null,
                                BigDecimal.valueOf(11),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.EXPONENTIAL,
                                ONE_SECOND,
                                null,
                                null,
                                null,
                                BigDecimal.valueOf(0.5),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.FIXED,
                                ONE_SECOND,
                                null,
                                null,
                                null,
                                null,
                                BigDecimal.valueOf(-0.1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.FIXED,
                                ONE_SECOND,
                                null,
                                null,
                                null,
                                null,
                                BigDecimal.valueOf(1.1)));
    }

    @Test
    void enforcesRetryStrategySpecificFields() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.FIXED,
                                ONE_SECOND,
                                null,
                                null,
                                ONE_SECOND,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1, RetryStrategy.LINEAR, ONE_SECOND, null, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.LINEAR,
                                ONE_SECOND,
                                null,
                                null,
                                ONE_SECOND,
                                BigDecimal.valueOf(2),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.EXPONENTIAL,
                                ONE_SECOND,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RetryPolicy(
                                1,
                                RetryStrategy.EXPONENTIAL,
                                ONE_SECOND,
                                null,
                                null,
                                ONE_SECOND,
                                BigDecimal.valueOf(2),
                                null));
    }

    @Test
    void validatesEdgesAndParameterMappings() {
        WorkflowEdge conditional =
                new WorkflowEdge(
                        "approved",
                        "decision",
                        "refund",
                        EdgeTransition.CONDITION,
                        "input.approved == true",
                        List.of(new ParameterMapping("request.amount", "input.amount")));
        assertEquals(
                "input.approved == true",
                conditional.when(),
                "Condition edges must preserve the guarded expression");
        assertThrows(
                NullPointerException.class,
                () -> new WorkflowEdge(EDGE_ID, START_ID, END_ID, null, null, List.of()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new WorkflowEdge(
                                EDGE_ID,
                                START_ID,
                                END_ID,
                                EdgeTransition.CONDITION,
                                null,
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowEdge(
                                EDGE_ID,
                                START_ID,
                                END_ID,
                                EdgeTransition.SUCCESS,
                                "input.ok",
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParameterMapping("bad-target!", "input.value"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParameterMapping("request.value", "now()"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParameterMapping("request.value", "x".repeat(2049)));
        ParameterMapping duplicate = new ParameterMapping("request.value", "input.value");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowEdge(
                                EDGE_ID,
                                START_ID,
                                END_ID,
                                EdgeTransition.SUCCESS,
                                null,
                                List.of(duplicate, duplicate)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowEdge(
                                EDGE_ID,
                                START_ID,
                                END_ID,
                                EdgeTransition.SUCCESS,
                                null,
                                Collections.nCopies(101, duplicate)));
    }

    @Test
    void keepsGraphAndSemanticValidationDeferredToLaterG3Tasks() {
        WorkflowNode duplicateStart = startNode();
        WorkflowDefinition structurallyValid =
                new WorkflowDefinition(
                        1,
                        "deferred-boundaries",
                        null,
                        1,
                        validTriggers(),
                        List.of(duplicateStart, duplicateStart),
                        List.of(
                                new WorkflowEdge(
                                        "dangling",
                                        "missing_from",
                                        "missing_to",
                                        EdgeTransition.CONDITION,
                                        "input +",
                                        List.of())),
                        emptyParameters(),
                        Map.of());

        assertEquals(
                2,
                structurallyValid.nodes().size(),
                "G3-01 must leave unique Start validation to G3-03");
        assertEquals(
                "missing_from",
                structurallyValid.edges().getFirst().from(),
                "G3-01 must leave edge reference validation to G3-03");
        assertTrue(
                structurallyValid.edges().getFirst().when().endsWith("+"),
                "G3-01 must leave expression grammar validation to G3-05");
    }

    @Test
    void validatesTriggerIdentityAndCopiesConfiguration() {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("authentication", "oidc");
        WorkflowTrigger trigger =
                new WorkflowTrigger("inbound_http", TriggerType.HTTP, configuration);
        configuration.put("authentication", "hmac");

        assertEquals(
                "oidc",
                trigger.configuration().get("authentication"),
                "Trigger configuration must be copied on construction");
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowTrigger("bad-id", TriggerType.MANUAL, Map.of()));
        assertThrows(
                NullPointerException.class, () -> new WorkflowTrigger(MANUAL_ID, null, Map.of()));
    }

    private static WorkflowDefinition validDefinition(
            int schemaVersion,
            String name,
            int catalogVersion,
            List<WorkflowTrigger> triggers,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges) {
        return new WorkflowDefinition(
                schemaVersion,
                name,
                null,
                catalogVersion,
                triggers,
                nodes,
                edges,
                emptyParameters(),
                Map.of());
    }

    private static WorkflowDefinition definitionWithMetadata(Map<String, Object> metadata) {
        return new WorkflowDefinition(
                1,
                VALID_NAME,
                null,
                1,
                validTriggers(),
                validNodes(),
                validEdges(),
                emptyParameters(),
                metadata);
    }

    private static List<WorkflowTrigger> validTriggers() {
        return List.of(new WorkflowTrigger(MANUAL_ID, TriggerType.MANUAL, null));
    }

    private static List<WorkflowNode> validNodes() {
        return List.of(startNode(), endNode());
    }

    private static List<WorkflowEdge> validEdges() {
        return List.of(successEdge("start_to_end", START_ID, END_ID));
    }

    private static WorkflowNode startNode() {
        return new WorkflowNode(START_ID, NodeType.START, 1, null, null, null, null);
    }

    private static WorkflowNode endNode() {
        return new WorkflowNode(END_ID, NodeType.END, 1, null, null, null, Map.of());
    }

    private static WorkflowEdge successEdge(String id, String from, String to) {
        return new WorkflowEdge(id, from, to, EdgeTransition.SUCCESS, null, List.of());
    }

    private static WorkflowParameters emptyParameters() {
        return new WorkflowParameters(null, null);
    }

    private static RetryPolicy fixedRetry() {
        return fixedRetry(3);
    }

    private static RetryPolicy fixedRetry(int maxAttempts) {
        return new RetryPolicy(
                maxAttempts,
                RetryStrategy.FIXED,
                ONE_SECOND,
                TEN_SECONDS,
                Duration.ofMinutes(1),
                null,
                null,
                BigDecimal.valueOf(0.2));
    }

    private static RetryPolicy linearRetry() {
        return new RetryPolicy(
                4,
                RetryStrategy.LINEAR,
                ONE_SECOND,
                TEN_SECONDS,
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                null,
                BigDecimal.valueOf(0.1));
    }
}
