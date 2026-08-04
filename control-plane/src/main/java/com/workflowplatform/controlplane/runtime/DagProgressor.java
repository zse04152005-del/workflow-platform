package com.workflowplatform.controlplane.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deterministic DAG readiness evaluator; it never mutates persisted state itself. */
public final class DagProgressor {

    private static final String PENDING = "PENDING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";

    public List<Decision> evaluate(List<Node> nodes, List<Edge> edges) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Map<String, String> statuses = new HashMap<>();
        for (Node node : nodes) {
            if (statuses.put(node.id(), node.status()) != null) {
                throw new IllegalArgumentException("duplicate DAG node " + node.id());
            }
        }
        if (edges.stream()
                .anyMatch(
                        edge ->
                                !statuses.containsKey(edge.from())
                                        || !statuses.containsKey(edge.to()))) {
            throw new IllegalArgumentException("DAG edge references an unknown node");
        }
        Map<String, List<Edge>> predecessors =
                edges.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Edge::to, Collectors.toCollection(ArrayList::new)));
        List<Decision> decisions = new ArrayList<>();
        for (Node node : nodes) {
            if (!PENDING.equals(node.status())) {
                continue;
            }
            List<Edge> incoming = predecessors.getOrDefault(node.id(), List.of());
            List<Edge> active = incoming.stream().filter(Edge::active).toList();
            boolean blocked =
                    active.stream().anyMatch(edge -> FAILED.equals(statuses.get(edge.from())));
            long succeeded =
                    active.stream()
                            .filter(edge -> SUCCEEDED.equals(statuses.get(edge.from())))
                            .count();
            boolean settled =
                    active.stream()
                            .allMatch(
                                    edge ->
                                            SUCCEEDED.equals(statuses.get(edge.from()))
                                                    || CANCELLED.equals(statuses.get(edge.from())));
            boolean unselected = !incoming.isEmpty() && active.isEmpty();
            boolean ready = incoming.isEmpty() || (!blocked && succeeded > 0 && settled);
            decisions.add(
                    new Decision(
                            node.id(),
                            blocked
                                            || unselected
                                            || (!incoming.isEmpty() && settled && succeeded == 0)
                                    ? DecisionType.CANCEL
                                    : ready ? DecisionType.READY : DecisionType.WAIT));
        }
        decisions.sort(Comparator.comparing(Decision::nodeId));
        return List.copyOf(decisions);
    }

    public record Node(String id, String status) {
        public Node {
            if (id == null || id.isBlank() || status == null || status.isBlank()) {
                throw new IllegalArgumentException("DAG node identity and status are required");
            }
        }
    }

    public record Edge(String from, String to, boolean active) {
        public Edge(String from, String to) {
            this(from, to, true);
        }

        public Edge {
            if (from == null || to == null || from.equals(to)) {
                throw new IllegalArgumentException("DAG edge must connect two distinct nodes");
            }
        }
    }

    public record Decision(String nodeId, DecisionType type) {}

    public enum DecisionType {
        READY,
        WAIT,
        CANCEL
    }
}
