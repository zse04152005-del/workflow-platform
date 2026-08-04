package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workflowplatform.contracts.dsl.EdgeTransition;
import com.workflowplatform.contracts.dsl.NodeType;
import com.workflowplatform.contracts.dsl.TriggerType;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import com.workflowplatform.contracts.dsl.WorkflowNode;
import com.workflowplatform.contracts.dsl.WorkflowParameters;
import com.workflowplatform.contracts.dsl.WorkflowTrigger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkflowGraphValidatorTests {

    private static final String START_NODE_ID = "start";
    private static final String END_NODE_ID = "end";
    private static final int MAXIMUM_GRAPH_NODES = 500;

    private final WorkflowDefinitionParser parser = new JacksonWorkflowDefinitionParser();
    private final WorkflowGraphValidator validator = new DeterministicWorkflowGraphValidator();

    @Test
    void acceptsLinearGraphAndReturnsCompilerMetadata() throws IOException {
        WorkflowDefinition definition = parseFixture("valid/linear.json");

        ValidatedWorkflowGraph result = validator.validate(definition);

        assertSame(definition, result.definition(), "Validation must preserve the immutable model");
        assertEquals(START_NODE_ID, result.startNodeId(), "The unique Start ID must be exposed");
        assertEquals(List.of("end"), result.endNodeIds(), "End IDs must preserve source order");
        assertEquals(
                List.of(START_NODE_ID, "task", "end"),
                result.topologicalNodeIds(),
                "Linear topological order must be deterministic");
    }

    @Test
    void acceptsConditionBranchesWithAnAlwaysDefault() throws IOException {
        ValidatedWorkflowGraph result =
                validator.validate(parseFixture("valid/condition-default.json"));

        assertEquals(
                List.of("approved", "fallback"),
                result.endNodeIds(),
                "Multiple End IDs must preserve definition order");
        assertEquals(
                List.of(START_NODE_ID, "decision", "approved", "fallback"),
                result.topologicalNodeIds(),
                "Ready nodes must be ordered by their definition index");
    }

    @Test
    void acceptsSuccessAndFailureRoutesToMultipleEnds() throws IOException {
        ValidatedWorkflowGraph result =
                validator.validate(parseFixture("valid/multiple-ends.json"));

        assertEquals(
                List.of("succeeded", "failed"),
                result.endNodeIds(),
                "Success and failure End IDs must preserve definition order");
        assertEquals(
                List.of(START_NODE_ID, "request", "succeeded", "failed"),
                result.topologicalNodeIds(),
                "Multiple ready End nodes must use definition order");
    }

    @Test
    void acceptsMaximumNodeDepthWithinBound() {
        ValidatedWorkflowGraph result = validator.validate(maximumDepthDefinition());

        assertEquals(
                MAXIMUM_GRAPH_NODES,
                result.topologicalNodeIds().size(),
                "The model's full 500-node bound must validate without stack overflow");
        assertEquals(
                START_NODE_ID,
                result.topologicalNodeIds().getFirst(),
                "Maximum-depth order must begin at Start");
        assertEquals(
                END_NODE_ID,
                result.topologicalNodeIds().getLast(),
                "Maximum-depth order must terminate at End");
    }

    @Test
    void rejectsMissingStart() throws IOException {
        assertFailure(
                "invalid/missing-start.json",
                WorkflowGraphValidationFailure.START_NODE_COUNT_INVALID);
    }

    @Test
    void rejectsMultipleStarts() throws IOException {
        assertFailure(
                "invalid/multiple-starts.json",
                WorkflowGraphValidationFailure.START_NODE_COUNT_INVALID);
    }

    @Test
    void rejectsMissingEnd() throws IOException {
        assertFailure("invalid/missing-end.json", WorkflowGraphValidationFailure.END_NODE_REQUIRED);
    }

    @Test
    void rejectsDuplicateNodeIds() throws IOException {
        assertFailure(
                "invalid/duplicate-node-id.json", WorkflowGraphValidationFailure.DUPLICATE_NODE_ID);
    }

    @Test
    void rejectsDuplicateEdgeIds() throws IOException {
        assertFailure(
                "invalid/duplicate-edge-id.json", WorkflowGraphValidationFailure.DUPLICATE_EDGE_ID);
    }

    @Test
    void rejectsUnknownEdgeSource() throws IOException {
        assertFailure(
                "invalid/dangling-source.json", WorkflowGraphValidationFailure.UNKNOWN_EDGE_SOURCE);
    }

    @Test
    void rejectsUnknownEdgeTarget() throws IOException {
        assertFailure(
                "invalid/dangling-target.json", WorkflowGraphValidationFailure.UNKNOWN_EDGE_TARGET);
    }

    @Test
    void rejectsSelfLoopsBeforeCycleAnalysis() throws IOException {
        assertFailure(
                "invalid/self-loop.json", WorkflowGraphValidationFailure.SELF_LOOP_NOT_ALLOWED);
    }

    @Test
    void rejectsIncomingEdgesToStart() throws IOException {
        assertFailure(
                "invalid/incoming-start.json",
                WorkflowGraphValidationFailure.INCOMING_EDGE_TO_START);
    }

    @Test
    void rejectsOutgoingEdgesFromEnd() throws IOException {
        assertFailure(
                "invalid/outgoing-end.json", WorkflowGraphValidationFailure.OUTGOING_EDGE_FROM_END);
    }

    @Test
    void rejectsTransitionsUnsupportedByTheSourceNodeType() throws IOException {
        assertFailure(
                "invalid/invalid-transition.json",
                WorkflowGraphValidationFailure.TRANSITION_NOT_ALLOWED);
    }

    @Test
    void rejectsDuplicateRoutes() throws IOException {
        assertFailure(
                "invalid/duplicate-route.json",
                WorkflowGraphValidationFailure.DUPLICATE_EDGE_ROUTE);
    }

    @Test
    void rejectsAlwaysCombinedWithOutcomeRoutes() throws IOException {
        assertFailure(
                "invalid/ambiguous-transition.json",
                WorkflowGraphValidationFailure.OUTGOING_TRANSITION_AMBIGUOUS);
    }

    @Test
    void rejectsStartWithMoreThanOneOutgoingEdge() throws IOException {
        assertFailure(
                "invalid/invalid-start-outdegree.json",
                WorkflowGraphValidationFailure.OUTGOING_EDGE_COUNT_INVALID);
    }

    @Test
    void rejectsConditionWithFewerThanTwoBranches() throws IOException {
        assertFailure(
                "invalid/invalid-condition-outdegree.json",
                WorkflowGraphValidationFailure.OUTGOING_EDGE_COUNT_INVALID);
    }

    @Test
    void rejectsNodesThatAreNotReachableFromStart() throws IOException {
        assertFailure(
                "invalid/unreachable-node.json", WorkflowGraphValidationFailure.NODE_UNREACHABLE);
    }

    @Test
    void rejectsNodesThatCannotReachAnyEnd() throws IOException {
        assertFailure(
                "invalid/cannot-reach-end.json",
                WorkflowGraphValidationFailure.NODE_CANNOT_REACH_END);
    }

    @Test
    void rejectsEveryDirectedCycleBecauseDslV1HasNoBoundedLoopConstruct() throws IOException {
        assertFailure("invalid/cycle.json", WorkflowGraphValidationFailure.CYCLE_NOT_ALLOWED);
    }

    @Test
    void reportsLocalViolationsInStableDefinitionOrder() throws IOException {
        WorkflowGraphValidationException exception =
                assertRejected(parseFixture("invalid/outgoing-end.json"));

        assertEquals(
                List.of(
                        new WorkflowGraphViolation(
                                WorkflowGraphValidationFailure.OUTGOING_EDGE_FROM_END,
                                "/edges/1/from"),
                        new WorkflowGraphViolation(
                                WorkflowGraphValidationFailure.INCOMING_EDGE_TO_START,
                                "/edges/1/to"),
                        new WorkflowGraphViolation(
                                WorkflowGraphValidationFailure.TRANSITION_NOT_ALLOWED,
                                "/edges/1/on")),
                exception.violations(),
                "Violation ordering must follow deterministic validation phases and source order");
        assertEquals(
                "Workflow graph rejected: graph_invalid (3)",
                exception.getMessage(),
                "The exception message must contain no user-controlled node or edge IDs");
        assertFalse(
                exception.getMessage().contains(START_NODE_ID),
                "The payload-safe message must not contain a node ID");
        assertFalse(
                exception.getMessage().contains("end_start"),
                "The payload-safe message must not contain an edge ID");
    }

    @Test
    void exposesAllStableFailureWireNames() {
        assertEquals(
                List.of(
                        "start_node_count_invalid",
                        "end_node_required",
                        "duplicate_node_id",
                        "duplicate_edge_id",
                        "unknown_edge_source",
                        "unknown_edge_target",
                        "self_loop_not_allowed",
                        "incoming_edge_to_start",
                        "outgoing_edge_from_end",
                        "transition_not_allowed",
                        "duplicate_edge_route",
                        "outgoing_transition_ambiguous",
                        "outgoing_edge_count_invalid",
                        "node_unreachable",
                        "node_cannot_reach_end",
                        "cycle_not_allowed"),
                List.of(WorkflowGraphValidationFailure.values()).stream()
                        .map(WorkflowGraphValidationFailure::wireName)
                        .toList(),
                "Graph failure wire names must remain stable and ordered");
    }

    @Test
    void keepsValidationResultsAndViolationsImmutable() throws IOException {
        ValidatedWorkflowGraph result = validator.validate(parseFixture("valid/linear.json"));
        WorkflowGraphValidationException exception =
                assertRejected(parseFixture("invalid/missing-start.json"));

        assertThrows(
                UnsupportedOperationException.class, () -> result.endNodeIds().add("another_end"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.topologicalNodeIds().add("another_node"));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        exception
                                .violations()
                                .add(
                                        new WorkflowGraphViolation(
                                                WorkflowGraphValidationFailure.END_NODE_REQUIRED,
                                                "/nodes")));
    }

    @Test
    void enforcesPayloadSafeViolationPointers() {
        assertThrows(NullPointerException.class, () -> new WorkflowGraphViolation(null, "/nodes"));
        assertThrows(
                NullPointerException.class,
                () ->
                        new WorkflowGraphViolation(
                                WorkflowGraphValidationFailure.END_NODE_REQUIRED, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowGraphViolation(
                                WorkflowGraphValidationFailure.END_NODE_REQUIRED, "nodes"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowGraphViolation(
                                WorkflowGraphValidationFailure.END_NODE_REQUIRED,
                                "/" + "x".repeat(128)));
    }

    @Test
    void rejectsNullDefinitionsAndEmptyExceptionPayloads() {
        assertThrows(NullPointerException.class, () -> validator.validate(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowGraphValidationException(List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new WorkflowGraphValidationException(null));
    }

    private void assertFailure(String fixture, WorkflowGraphValidationFailure expected)
            throws IOException {
        WorkflowGraphValidationException exception = assertRejected(parseFixture(fixture));
        assertTrue(
                exception.violations().stream()
                        .anyMatch(violation -> violation.failure() == expected),
                () -> expected.wireName() + " missing from " + exception.violations());
        assertTrue(
                exception.violations().stream()
                        .allMatch(violation -> violation.path().startsWith("/")),
                "Every violation must use a JSON Pointer");
        assertNotNull(exception.getMessage(), "Graph rejection must always expose a safe message");
        assertEquals(
                "Workflow graph rejected: graph_invalid (" + exception.violations().size() + ")",
                exception.getMessage(),
                "Graph failure message must be payload-safe and deterministic");
    }

    private WorkflowGraphValidationException assertRejected(WorkflowDefinition definition) {
        return assertThrows(
                WorkflowGraphValidationException.class,
                () -> validator.validate(definition),
                "Graph validation must reject the invalid definition");
    }

    private WorkflowDefinition parseFixture(String relative) throws IOException {
        Path fixture = repositoryRoot().resolve("tests/contracts/dsl/graph").resolve(relative);
        assertTrue(Files.isRegularFile(fixture), "Missing graph fixture: " + fixture);
        return parser.parse(Files.readAllBytes(fixture), WorkflowDefinitionSourceFormat.JSON)
                .definition();
    }

    private static WorkflowDefinition maximumDepthDefinition() {
        List<WorkflowNode> nodes =
                IntStream.range(0, MAXIMUM_GRAPH_NODES)
                        .mapToObj(WorkflowGraphValidatorTests::maximumDepthNode)
                        .toList();
        List<WorkflowEdge> edges =
                IntStream.range(0, MAXIMUM_GRAPH_NODES - 1)
                        .mapToObj(
                                index ->
                                        new WorkflowEdge(
                                                "edge_" + index,
                                                maximumDepthNodeId(index),
                                                maximumDepthNodeId(index + 1),
                                                EdgeTransition.SUCCESS,
                                                null,
                                                List.of()))
                        .toList();
        return new WorkflowDefinition(
                1,
                "maximum-depth",
                null,
                1,
                List.of(new WorkflowTrigger("manual", TriggerType.MANUAL, Map.of())),
                nodes,
                edges,
                new WorkflowParameters(null, null),
                Map.of());
    }

    private static WorkflowNode maximumDepthNode(int index) {
        NodeType type =
                index == 0
                        ? NodeType.START
                        : index == MAXIMUM_GRAPH_NODES - 1 ? NodeType.END : NodeType.DELAY;
        return new WorkflowNode(maximumDepthNodeId(index), type, 1, null, null, null, Map.of());
    }

    private static String maximumDepthNodeId(int index) {
        if (index == 0) {
            return START_NODE_ID;
        }
        return index == MAXIMUM_GRAPH_NODES - 1 ? END_NODE_ID : "node_" + index;
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
}
