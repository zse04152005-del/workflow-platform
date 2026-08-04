package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.EdgeTransition;
import com.workflowplatform.contracts.dsl.NodeType;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import com.workflowplatform.contracts.dsl.WorkflowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import org.springframework.stereotype.Component;

/** Deterministic, bounded graph validator for workflow DSL schema version 1. */
@Component
final class DeterministicWorkflowGraphValidator implements WorkflowGraphValidator {

    private static final int UNVISITED = 0;
    private static final int VISITING = 1;
    private static final int VISITED = 2;
    private static final int REQUIRED_START_COUNT = 1;

    @Override
    public ValidatedWorkflowGraph validate(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        List<WorkflowGraphViolation> violations = new ArrayList<>();
        NodeIndex nodeIndex = indexNodes(definition.nodes(), violations);
        Connections connections =
                indexEdges(definition.nodes(), definition.edges(), nodeIndex, violations);
        validateOutgoingRules(definition.nodes(), connections.outgoing(), violations);

        if (violations.isEmpty()) {
            validateTopology(definition.nodes(), nodeIndex, connections, violations);
        }
        if (!violations.isEmpty()) {
            throw new WorkflowGraphValidationException(violations);
        }

        List<String> endNodeIds =
                nodeIndex.endIndexes().stream()
                        .map(index -> definition.nodes().get(index).id())
                        .toList();
        return new ValidatedWorkflowGraph(
                definition,
                definition.nodes().get(nodeIndex.startIndex()).id(),
                endNodeIds,
                topologicalOrder(definition.nodes(), connections));
    }

    private static NodeIndex indexNodes(
            List<WorkflowNode> nodes, List<WorkflowGraphViolation> violations) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        List<Integer> endIndexes = new ArrayList<>();
        int startCount = 0;
        int startIndex = -1;
        for (int index = 0; index < nodes.size(); index++) {
            WorkflowNode node = nodes.get(index);
            if (indexes.putIfAbsent(node.id(), index) != null) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.DUPLICATE_NODE_ID,
                                nodePath(index, "id")));
            }
            if (node.type() == NodeType.START) {
                startCount++;
                if (startIndex < 0) {
                    startIndex = index;
                }
            } else if (node.type() == NodeType.END) {
                endIndexes.add(index);
            }
        }
        if (startCount != REQUIRED_START_COUNT) {
            violations.add(
                    violation(WorkflowGraphValidationFailure.START_NODE_COUNT_INVALID, "/nodes"));
        }
        if (endIndexes.isEmpty()) {
            violations.add(violation(WorkflowGraphValidationFailure.END_NODE_REQUIRED, "/nodes"));
        }
        return new NodeIndex(indexes, startIndex, List.copyOf(endIndexes));
    }

    private static Connections indexEdges(
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            NodeIndex nodeIndex,
            List<WorkflowGraphViolation> violations) {
        List<List<IndexedEdge>> outgoing = emptyEdgeLists(nodes.size());
        List<List<IndexedEdge>> incoming = emptyEdgeLists(nodes.size());
        Map<String, Integer> edgeIds = new LinkedHashMap<>();
        Map<RouteKey, Integer> routes = new LinkedHashMap<>();

        for (int index = 0; index < edges.size(); index++) {
            WorkflowEdge edge = edges.get(index);
            if (edgeIds.putIfAbsent(edge.id(), index) != null) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.DUPLICATE_EDGE_ID,
                                edgePath(index, "id")));
            }
            Integer fromIndex = nodeIndex.indexes().get(edge.from());
            Integer toIndex = nodeIndex.indexes().get(edge.to());
            if (fromIndex == null) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.UNKNOWN_EDGE_SOURCE,
                                edgePath(index, "from")));
            }
            if (toIndex == null) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.UNKNOWN_EDGE_TARGET,
                                edgePath(index, "to")));
            }
            if (fromIndex != null && toIndex != null) {
                validateLocalEdge(nodes, edge, index, fromIndex, toIndex, routes, violations);
                connect(index, edge, fromIndex, toIndex, outgoing, incoming);
            }
        }
        return new Connections(outgoing, incoming);
    }

    private static void connect(
            int edgeIndex,
            WorkflowEdge edge,
            int fromIndex,
            int toIndex,
            List<List<IndexedEdge>> outgoing,
            List<List<IndexedEdge>> incoming) {
        IndexedEdge indexed = new IndexedEdge(edgeIndex, edge, fromIndex, toIndex);
        outgoing.get(fromIndex).add(indexed);
        incoming.get(toIndex).add(indexed);
    }

    private static void validateLocalEdge(
            List<WorkflowNode> nodes,
            WorkflowEdge edge,
            int edgeIndex,
            int fromIndex,
            int toIndex,
            Map<RouteKey, Integer> routes,
            List<WorkflowGraphViolation> violations) {
        WorkflowNode source = nodes.get(fromIndex);
        WorkflowNode target = nodes.get(toIndex);
        if (fromIndex == toIndex) {
            violations.add(
                    violation(
                            WorkflowGraphValidationFailure.SELF_LOOP_NOT_ALLOWED,
                            edgePath(edgeIndex, null)));
        }
        if (source.type() == NodeType.END) {
            violations.add(
                    violation(
                            WorkflowGraphValidationFailure.OUTGOING_EDGE_FROM_END,
                            edgePath(edgeIndex, "from")));
        }
        if (target.type() == NodeType.START) {
            violations.add(
                    violation(
                            WorkflowGraphValidationFailure.INCOMING_EDGE_TO_START,
                            edgePath(edgeIndex, "to")));
        }
        if (!transitionAllowed(source.type(), edge.on())) {
            violations.add(
                    violation(
                            WorkflowGraphValidationFailure.TRANSITION_NOT_ALLOWED,
                            edgePath(edgeIndex, "on")));
        }
        RouteKey route = new RouteKey(edge.from(), edge.to(), edge.on());
        if (routes.putIfAbsent(route, edgeIndex) != null) {
            violations.add(
                    violation(
                            WorkflowGraphValidationFailure.DUPLICATE_EDGE_ROUTE,
                            edgePath(edgeIndex, null)));
        }
    }

    private static void validateOutgoingRules(
            List<WorkflowNode> nodes,
            List<List<IndexedEdge>> outgoing,
            List<WorkflowGraphViolation> violations) {
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            WorkflowNode node = nodes.get(nodeIndex);
            List<IndexedEdge> edges = outgoing.get(nodeIndex);
            if (outgoingCountInvalid(node.type(), edges)) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.OUTGOING_EDGE_COUNT_INVALID,
                                nodePath(nodeIndex, null)));
            }
            if (transitionsAmbiguous(node.type(), edges)) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.OUTGOING_TRANSITION_AMBIGUOUS,
                                nodePath(nodeIndex, null)));
            }
        }
    }

    private static boolean outgoingCountInvalid(NodeType type, List<IndexedEdge> edges) {
        return switch (type) {
            case START -> edges.size() != 1;
            case END -> false;
            case CONDITION ->
                    edges.size() < 2
                            || edges.stream()
                                    .noneMatch(
                                            edge -> edge.edge().on() == EdgeTransition.CONDITION);
            case HTTP, DELAY, HUMAN_TASK -> edges.isEmpty();
        };
    }

    private static boolean transitionsAmbiguous(NodeType type, List<IndexedEdge> edges) {
        EnumMap<EdgeTransition, Integer> counts = new EnumMap<>(EdgeTransition.class);
        for (IndexedEdge edge : edges) {
            counts.merge(edge.edge().on(), 1, Integer::sum);
        }
        for (Map.Entry<EdgeTransition, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1 && entry.getKey() != EdgeTransition.CONDITION) {
                return true;
            }
        }
        return type != NodeType.CONDITION
                && counts.containsKey(EdgeTransition.ALWAYS)
                && edges.size() > 1;
    }

    private static boolean transitionAllowed(NodeType type, EdgeTransition transition) {
        return switch (type) {
            case START ->
                    transition == EdgeTransition.SUCCESS || transition == EdgeTransition.ALWAYS;
            case END -> false;
            case CONDITION ->
                    transition == EdgeTransition.CONDITION || transition == EdgeTransition.ALWAYS;
            case HTTP, DELAY, HUMAN_TASK -> transition != EdgeTransition.CONDITION;
        };
    }

    private static void validateTopology(
            List<WorkflowNode> nodes,
            NodeIndex nodeIndex,
            Connections connections,
            List<WorkflowGraphViolation> violations) {
        boolean[] reachable = reachableFromStart(nodeIndex.startIndex(), connections.outgoing());
        for (int index = 0; index < nodes.size(); index++) {
            if (!reachable[index]) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.NODE_UNREACHABLE,
                                nodePath(index, null)));
            }
        }

        boolean[] reachesEnd = reachesAnyEnd(nodeIndex.endIndexes(), connections.incoming());
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).type() != NodeType.END && !reachesEnd[index]) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.NODE_CANNOT_REACH_END,
                                nodePath(index, null)));
            }
        }
        findCycleEdges(connections.outgoing(), violations);
    }

    private static boolean[] reachableFromStart(int startIndex, List<List<IndexedEdge>> outgoing) {
        boolean[] visited = new boolean[outgoing.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        visited[startIndex] = true;
        pending.add(startIndex);
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            for (IndexedEdge edge : outgoing.get(current)) {
                if (!visited[edge.toIndex()]) {
                    visited[edge.toIndex()] = true;
                    pending.addLast(edge.toIndex());
                }
            }
        }
        return visited;
    }

    private static boolean[] reachesAnyEnd(
            List<Integer> endIndexes, List<List<IndexedEdge>> incoming) {
        boolean[] visited = new boolean[incoming.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int endIndex : endIndexes) {
            visited[endIndex] = true;
            pending.addLast(endIndex);
        }
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            for (IndexedEdge edge : incoming.get(current)) {
                if (!visited[edge.fromIndex()]) {
                    visited[edge.fromIndex()] = true;
                    pending.addLast(edge.fromIndex());
                }
            }
        }
        return visited;
    }

    private static void findCycleEdges(
            List<List<IndexedEdge>> outgoing, List<WorkflowGraphViolation> violations) {
        int[] states = new int[outgoing.size()];
        for (int nodeIndex = 0; nodeIndex < outgoing.size(); nodeIndex++) {
            if (states[nodeIndex] == UNVISITED) {
                visit(nodeIndex, outgoing, states, violations);
            }
        }
    }

    private static void visit(
            int nodeIndex,
            List<List<IndexedEdge>> outgoing,
            int[] states,
            List<WorkflowGraphViolation> violations) {
        states[nodeIndex] = VISITING;
        for (IndexedEdge edge : outgoing.get(nodeIndex)) {
            if (states[edge.toIndex()] == UNVISITED) {
                visit(edge.toIndex(), outgoing, states, violations);
            } else if (states[edge.toIndex()] == VISITING) {
                violations.add(
                        violation(
                                WorkflowGraphValidationFailure.CYCLE_NOT_ALLOWED,
                                edgePath(edge.index(), null)));
            }
        }
        states[nodeIndex] = VISITED;
    }

    private static List<String> topologicalOrder(
            List<WorkflowNode> nodes, Connections connections) {
        int[] inDegree = new int[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            inDegree[index] = connections.incoming().get(index).size();
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int index = 0; index < inDegree.length; index++) {
            if (inDegree[index] == 0) {
                ready.add(index);
            }
        }
        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            int current = ready.remove();
            order.add(nodes.get(current).id());
            for (IndexedEdge edge : connections.outgoing().get(current)) {
                inDegree[edge.toIndex()]--;
                if (inDegree[edge.toIndex()] == 0) {
                    ready.add(edge.toIndex());
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalStateException(
                    "Validated graph did not produce a complete topological order");
        }
        return List.copyOf(order);
    }

    private static List<List<IndexedEdge>> emptyEdgeLists(int size) {
        List<List<IndexedEdge>> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(new ArrayList<>());
        }
        return values;
    }

    private static WorkflowGraphViolation violation(
            WorkflowGraphValidationFailure failure, String path) {
        return new WorkflowGraphViolation(failure, path);
    }

    private static String nodePath(int index, String field) {
        return field == null ? "/nodes/" + index : "/nodes/" + index + '/' + field;
    }

    private static String edgePath(int index, String field) {
        return field == null ? "/edges/" + index : "/edges/" + index + '/' + field;
    }

    private record NodeIndex(
            Map<String, Integer> indexes, int startIndex, List<Integer> endIndexes) {}

    private record IndexedEdge(int index, WorkflowEdge edge, int fromIndex, int toIndex) {}

    private record Connections(
            List<List<IndexedEdge>> outgoing, List<List<IndexedEdge>> incoming) {}

    private record RouteKey(String from, String to, EdgeTransition transition) {}
}
