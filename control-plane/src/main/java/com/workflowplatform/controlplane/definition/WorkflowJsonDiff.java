package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic bounded JSON diff with size-capped value previews. */
final class WorkflowJsonDiff {

    static final int MAXIMUM_PUBLIC_CHANGES = 1000;
    private static final int MAXIMUM_PREVIEW_BYTES = 4096;

    WorkflowVersionDiff compare(
            WorkflowSnapshot from,
            JsonNode fromDefinition,
            WorkflowSnapshot to,
            JsonNode toDefinition) {
        DiffAccumulator accumulator = new DiffAccumulator();
        compare("", fromDefinition, toDefinition, accumulator);
        return new WorkflowVersionDiff(
                from.workflowId(),
                reference(from),
                reference(to),
                accumulator.count,
                accumulator.count > accumulator.changes.size(),
                accumulator.changes);
    }

    private static void compare(
            String path, JsonNode before, JsonNode after, DiffAccumulator accumulator) {
        if (before.equals(after)) {
            return;
        }
        if (before.isObject() && after.isObject()) {
            Set<String> fields = new TreeSet<>();
            before.fieldNames().forEachRemaining(fields::add);
            after.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                String child = path + '/' + escape(field);
                if (!before.has(field)) {
                    accumulator.add("add", child, null, after.get(field));
                } else if (!after.has(field)) {
                    accumulator.add("remove", child, before.get(field), null);
                } else {
                    compare(child, before.get(field), after.get(field), accumulator);
                }
            }
            return;
        }
        if (before.isArray() && after.isArray()) {
            int maximum = Math.max(before.size(), after.size());
            for (int index = 0; index < maximum; index++) {
                String child = path + '/' + index;
                if (index >= before.size()) {
                    accumulator.add("add", child, null, after.get(index));
                } else if (index >= after.size()) {
                    accumulator.add("remove", child, before.get(index), null);
                } else {
                    compare(child, before.get(index), after.get(index), accumulator);
                }
            }
            return;
        }
        accumulator.add("replace", path.isEmpty() ? "/" : path, before, after);
    }

    private static WorkflowVersionDiff.VersionReference reference(WorkflowSnapshot snapshot) {
        if (snapshot.version() == null) {
            throw new IllegalArgumentException("Version diff requires published snapshots");
        }
        return new WorkflowVersionDiff.VersionReference(
                snapshot.version(), snapshot.checksum(), snapshot.capturedAt());
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static JsonNode preview(JsonNode value) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAXIMUM_PREVIEW_BYTES) {
            return value.deepCopy();
        }
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("type", value.getNodeType().name().toLowerCase(java.util.Locale.ROOT));
        summary.put("sha256", sha256(bytes));
        summary.put("truncated", true);
        return summary;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    private static final class DiffAccumulator {

        private int count;
        private final List<WorkflowVersionDiff.Change> changes = new ArrayList<>();

        private void add(String operation, String path, JsonNode before, JsonNode after) {
            count++;
            if (changes.size() < MAXIMUM_PUBLIC_CHANGES) {
                changes.add(
                        new WorkflowVersionDiff.Change(
                                operation, path, preview(before), preview(after)));
            }
        }
    }
}
