package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workflowplatform.contracts.dsl.EdgeTransition;
import com.workflowplatform.contracts.dsl.NodeType;
import com.workflowplatform.contracts.dsl.ParameterMapping;
import com.workflowplatform.contracts.dsl.TriggerType;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import com.workflowplatform.contracts.dsl.WorkflowNode;
import com.workflowplatform.contracts.dsl.WorkflowParameters;
import com.workflowplatform.contracts.dsl.WorkflowTrigger;
import com.workflowplatform.controlplane.definition.ExpressionSandboxException.Reason;
import com.workflowplatform.controlplane.definition.IntegrationResource.Kind;
import com.workflowplatform.controlplane.definition.IntegrationResource.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class WorkflowExpressionCompilerTests {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CONNECTOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String CONNECTOR_REFERENCE = "connector.orders";

    private final WorkflowGraphValidator graphValidator = new DeterministicWorkflowGraphValidator();
    private final WorkflowConfigurationValidator configurationValidator =
            new DeterministicWorkflowConfigurationValidator();
    private final WorkflowExpressionCompiler compiler =
            new DeterministicWorkflowExpressionCompiler();

    @Test
    void compilesEveryCelAndJsonPathSiteIntoImmutablePayloadFreeMetadata() {
        CompiledWorkflowExpressions result =
                compile(definition(validNodes(), validEdges(), inputSchema()));

        assertEquals(8, result.expressions().size());
        CompiledExpression condition = result.expressions().get("/nodes/2/config/expression");
        assertEquals(ExpressionLanguage.CEL, condition.language());
        assertEquals(ExpressionValueType.BOOLEAN, condition.resultType());
        assertEquals(ExpressionValueType.BOOLEAN, condition.expectedType());
        assertTrue(condition.sourceSha256().matches("^[0-9a-f]{64}$"));
        assertFalse(condition.toString().contains("input.approved"));
        CompiledExpression header = result.expressions().get("/nodes/1/config/headers/X-Trace-Id");
        assertEquals(ExpressionLanguage.JSON_PATH, header.language());
        assertEquals(ExpressionValueType.STRING, header.resultType());
        assertEquals(ExpressionSandboxPolicy.DEFAULT, result.sandboxPolicy());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.expressions().put("/changed", condition));
    }

    @Test
    void appliesCelPrecedenceNumericTypingAndBracketAccess() {
        List<WorkflowNode> nodes = validNodes();
        nodes.set(
                2,
                node(
                        "condition",
                        NodeType.CONDITION,
                        Map.of(
                                "config",
                                Map.of(
                                        "expression",
                                        "input['approved'] && input.amount + 5 * 2 <= 510"))));

        CompiledExpression expression =
                compile(definition(nodes, validEdges(), inputSchema()))
                        .expressions()
                        .get("/nodes/2/config/expression");

        assertEquals(ExpressionValueType.BOOLEAN, expression.resultType());
        assertTrue(expression.astNodeCount() >= 9);
        assertTrue(expression.estimatedEvaluationSteps() <= 512);
    }

    @Test
    void rejectsMalformedCelUnknownRootsAndClosedSchemaFields() {
        assertFailure(
                conditionDefinition("input.approved &&"),
                WorkflowExpressionCompilationFailure.SYNTAX_INVALID,
                "/nodes/2/config/expression");
        assertFailure(
                conditionDefinition("secret.value == 'x'"),
                WorkflowExpressionCompilationFailure.IDENTIFIER_UNKNOWN,
                "/nodes/2/config/expression");
        assertFailure(
                conditionDefinition("input.missing == null"),
                WorkflowExpressionCompilationFailure.IDENTIFIER_UNKNOWN,
                "/nodes/2/config/expression");
    }

    @Test
    void rejectsStaticallyWrongConditionAndStringResultTypes() {
        assertFailure(
                conditionDefinition("input.owner"),
                WorkflowExpressionCompilationFailure.TYPE_MISMATCH,
                "/nodes/2/config/expression");

        List<WorkflowNode> nodes = validNodes();
        nodes.set(
                4,
                node(
                        "review",
                        NodeType.HUMAN_TASK,
                        Map.of(
                                "config",
                                Map.of(
                                        "assigneeExpression",
                                        "input.approved",
                                        "formSchema",
                                        Map.of("type", "object"),
                                        "deadline",
                                        "PT2H",
                                        "timeoutPolicy",
                                        "fail"))));
        assertFailure(
                definition(nodes, validEdges(), inputSchema()),
                WorkflowExpressionCompilationFailure.TYPE_MISMATCH,
                "/nodes/4/config/assigneeExpression");
    }

    @Test
    void rejectsFunctionInvocationAndNondeterministicOrReflectiveOperations() {
        assertFailure(
                conditionDefinition("size(input.items) > 0"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/2/config/expression");
        assertFailure(
                conditionDefinition("input.owner.size() > 0"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/2/config/expression");
        assertFailure(
                conditionDefinition("Runtime.value == null"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/2/config/expression");
    }

    @Test
    void rejectsJsonPathRecursionFiltersAndNonStringSelections() {
        assertFailure(
                delayDefinition("input..expires_at"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/3/config/expiresAtExpression");
        assertFailure(
                delayDefinition("input.items[?(@.enabled)]"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/3/config/expiresAtExpression");
        assertFailure(
                delayDefinition("$.input.expires_at"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/3/config/expiresAtExpression");
        assertFailure(
                delayDefinition("input.*"),
                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN,
                "/nodes/3/config/expiresAtExpression");
        assertFailure(
                delayDefinition("input.approved"),
                WorkflowExpressionCompilationFailure.TYPE_MISMATCH,
                "/nodes/3/config/expiresAtExpression");
    }

    @Test
    void rejectsCelNestingAndJsonPathSegmentBudgetExhaustion() {
        String nested = "(".repeat(33) + "input.approved" + ")".repeat(33);
        assertFailure(
                conditionDefinition(nested),
                WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED,
                "/nodes/2/config/expression");

        String path = "response" + ".value".repeat(33);
        assertFailure(
                delayDefinition(path),
                WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED,
                "/nodes/3/config/expiresAtExpression");
    }

    @Test
    void runtimeGuardEnforcesStepsAndMonotonicTimeout() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ExpressionSandboxPolicy policy = ExpressionSandboxPolicy.DEFAULT;
        ExpressionEvaluationGuard guard = ExpressionEvaluationGuard.start(policy, clock::get);

        guard.consumeSteps(512);
        assertEquals(512, guard.consumedSteps());
        assertEquals(
                Reason.STEP_LIMIT,
                assertThrows(ExpressionSandboxException.class, () -> guard.consumeSteps(1))
                        .reason());

        ExpressionEvaluationGuard timed = ExpressionEvaluationGuard.start(policy, clock::get);
        clock.addAndGet(25_000_001L);
        assertEquals(
                Reason.TIME_LIMIT,
                assertThrows(ExpressionSandboxException.class, () -> timed.consumeSteps(1))
                        .reason());
        assertThrows(IllegalArgumentException.class, () -> timed.consumeSteps(0));
    }

    @Test
    void runtimeGuardEnforcesResultBytesDepthEntriesAndJsonTypes() {
        ExpressionSandboxPolicy bytesLimited =
                new ExpressionSandboxPolicy(10, 10, 10, 10, 10, 10, 12, 2, 2);
        ExpressionSandboxPolicy depthLimited =
                new ExpressionSandboxPolicy(10, 10, 10, 10, 10, 10, 1_000, 2, 100);
        ExpressionSandboxPolicy entriesLimited =
                new ExpressionSandboxPolicy(10, 10, 10, 10, 10, 10, 1_000, 10, 2);
        assertSandbox(Reason.RESULT_BYTES, bytesLimited, "01234567890");
        assertSandbox(Reason.RESULT_DEPTH, depthLimited, List.of(List.of(List.of("x"))));
        assertSandbox(Reason.RESULT_ENTRIES, entriesLimited, List.of(1, 2, 3));
        assertSandbox(Reason.RESULT_TYPE, depthLimited, Map.of(1, "not-a-json-object"));

        ExpressionEvaluationGuard valid = ExpressionEvaluationGuard.start(depthLimited, () -> 0L);
        valid.checkResult(Map.of("ok", true));
    }

    @Test
    void compilationFailuresAreStableBoundedAndDoNotEchoPayloads() {
        WorkflowExpressionCompilationException exception =
                assertThrows(
                        WorkflowExpressionCompilationException.class,
                        () -> compile(conditionDefinition("input.owner")));
        assertEquals(
                "Workflow expressions rejected: expression_invalid (1)", exception.getMessage());
        assertFalse(exception.getMessage().contains("input.owner"));
        assertEquals(
                List.of(
                        "expression_syntax_invalid",
                        "expression_type_mismatch",
                        "expression_identifier_unknown",
                        "expression_operation_forbidden",
                        "expression_budget_exceeded"),
                List.of(WorkflowExpressionCompilationFailure.values()).stream()
                        .map(WorkflowExpressionCompilationFailure::wireName)
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowExpressionCompilationException(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowExpressionViolation(
                                WorkflowExpressionCompilationFailure.SYNTAX_INVALID,
                                "not-a-pointer"));
        assertThrows(NullPointerException.class, () -> compiler.compile(null));
    }

    private void assertFailure(
            WorkflowDefinition definition,
            WorkflowExpressionCompilationFailure failure,
            String path) {
        WorkflowExpressionCompilationException exception =
                assertThrows(
                        WorkflowExpressionCompilationException.class, () -> compile(definition));
        assertTrue(
                exception.violations().contains(new WorkflowExpressionViolation(failure, path)),
                () -> "Missing " + failure + " at " + path + ": " + exception.violations());
    }

    private static void assertSandbox(
            Reason reason, ExpressionSandboxPolicy policy, Object result) {
        ExpressionEvaluationGuard guard = ExpressionEvaluationGuard.start(policy, () -> 0L);
        assertEquals(
                reason,
                assertThrows(ExpressionSandboxException.class, () -> guard.checkResult(result))
                        .reason());
    }

    private CompiledWorkflowExpressions compile(WorkflowDefinition definition) {
        ValidatedWorkflowGraph graph = graphValidator.validate(definition);
        ValidatedWorkflowConfiguration configuration =
                configurationValidator.validate(graph, context());
        return compiler.compile(configuration);
    }

    private static WorkflowDefinition conditionDefinition(String expression) {
        List<WorkflowNode> nodes = validNodes();
        nodes.set(
                2,
                node(
                        "condition",
                        NodeType.CONDITION,
                        Map.of("config", Map.of("expression", expression))));
        return definition(nodes, validEdges(), inputSchema());
    }

    private static WorkflowDefinition delayDefinition(String expression) {
        List<WorkflowNode> nodes = validNodes();
        nodes.set(
                3,
                node(
                        "wait",
                        NodeType.DELAY,
                        Map.of("config", Map.of("expiresAtExpression", expression))));
        return definition(nodes, validEdges(), inputSchema());
    }

    private static WorkflowDefinition definition(
            List<WorkflowNode> nodes, List<WorkflowEdge> edges, Map<String, Object> schema) {
        return new WorkflowDefinition(
                1,
                "expression-validation",
                null,
                1,
                List.of(new WorkflowTrigger("manual", TriggerType.MANUAL, Map.of())),
                nodes,
                edges,
                new WorkflowParameters(schema, Map.of()),
                Map.of());
    }

    private static List<WorkflowNode> validNodes() {
        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(node("start", NodeType.START, Map.of()));
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("connectorRef", CONNECTOR_REFERENCE);
        http.put("method", "POST");
        http.put("headers", Map.of("X-Trace-Id", "input.trace_id"));
        http.put("bodyExpression", "input.payload");
        http.put("resultExpression", "response.body");
        nodes.add(node("invoke", NodeType.HTTP, Map.of("config", http)));
        nodes.add(
                node(
                        "condition",
                        NodeType.CONDITION,
                        Map.of(
                                "config",
                                Map.of("expression", "input.approved && input.amount <= 500"))));
        nodes.add(
                node(
                        "wait",
                        NodeType.DELAY,
                        Map.of("config", Map.of("expiresAtExpression", "input.expires_at"))));
        nodes.add(
                node(
                        "review",
                        NodeType.HUMAN_TASK,
                        Map.of(
                                "config",
                                Map.of(
                                        "assigneeExpression",
                                        "input.owner",
                                        "formSchema",
                                        Map.of("type", "object"),
                                        "deadline",
                                        "PT2H",
                                        "timeoutPolicy",
                                        "fail"))));
        nodes.add(node("end", NodeType.END, Map.of()));
        return nodes;
    }

    private static List<WorkflowEdge> validEdges() {
        return List.of(
                new WorkflowEdge(
                        "start_invoke",
                        "start",
                        "invoke",
                        EdgeTransition.SUCCESS,
                        null,
                        List.of(new ParameterMapping("request.traceId", "input.trace_id"))),
                edge("invoke_condition", "invoke", "condition", EdgeTransition.SUCCESS, null),
                edge(
                        "condition_wait",
                        "condition",
                        "wait",
                        EdgeTransition.CONDITION,
                        "input.approved == true"),
                edge("condition_review", "condition", "review", EdgeTransition.ALWAYS, null),
                edge("wait_review", "wait", "review", EdgeTransition.SUCCESS, null),
                edge("review_end", "review", "end", EdgeTransition.SUCCESS, null));
    }

    private static WorkflowEdge edge(
            String id, String from, String to, EdgeTransition transition, String when) {
        return new WorkflowEdge(id, from, to, transition, when, List.of());
    }

    private static WorkflowNode node(String id, NodeType type, Map<String, Object> configuration) {
        return new WorkflowNode(id, type, 1, null, null, null, configuration);
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("approved", Map.of("type", "boolean"));
        properties.put("amount", Map.of("type", "number"));
        properties.put("owner", Map.of("type", "string"));
        properties.put("expires_at", Map.of("type", "string"));
        properties.put("trace_id", Map.of("type", "string"));
        properties.put("payload", Map.of("type", "object"));
        properties.put("items", Map.of("type", "array", "items", Map.of("type", "object")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static WorkflowConfigurationValidationContext context() {
        IntegrationResource connector =
                new IntegrationResource(
                        CONNECTOR_ID,
                        Kind.CONNECTOR,
                        CONNECTOR_REFERENCE,
                        TENANT_ID,
                        PROJECT_ID,
                        Status.ACTIVE,
                        false);
        return new WorkflowConfigurationValidationContext(
                TENANT_ID,
                PROJECT_ID,
                List.of(connector),
                Set.of(new ResourceUseGrant(Kind.CONNECTOR, CONNECTOR_ID)));
    }
}
