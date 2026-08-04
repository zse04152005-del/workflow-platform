package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

final class WorkflowDefinitionCanonicalizer {

    CanonicalDocument canonicalize(ObjectNode source, int maximumBytes) {
        ObjectNode normalized = (ObjectNode) normalize(source);
        BoundedCanonicalOutput output = new BoundedCanonicalOutput(maximumBytes);
        writeNode(normalized, output);
        byte[] bytes = output.toByteArray();
        return new CanonicalDocument(
                normalized, new String(bytes, StandardCharsets.UTF_8), bytes.length);
    }

    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode normalized = JsonNodeFactory.instance.objectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> normalized.set(name, normalize(value)));
            return normalized;
        }
        if (node.isArray()) {
            ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> normalized.add(normalize(value)));
            return normalized;
        }
        if (node.isIntegralNumber()) {
            return BigIntegerNode.valueOf(node.bigIntegerValue());
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal normalized = node.decimalValue().stripTrailingZeros();
            if (normalized.signum() == 0) {
                normalized = BigDecimal.ZERO;
            }
            return DecimalNode.valueOf(normalized);
        }
        if (node.isTextual() || node.isBoolean() || node.isNull()) {
            return node.deepCopy();
        }
        throw new IllegalArgumentException("Workflow source contains a non-JSON value");
    }

    private void writeNode(JsonNode node, BoundedCanonicalOutput output) {
        if (node.isObject()) {
            writeObject((ObjectNode) node, output);
        } else if (node.isArray()) {
            writeArray((ArrayNode) node, output);
        } else if (node.isTextual()) {
            output.writeQuoted(node.textValue());
        } else if (node.isIntegralNumber()) {
            output.writeAscii(node.bigIntegerValue().toString());
        } else if (node.isFloatingPointNumber()) {
            output.writePlainDecimal(node.decimalValue());
        } else if (node.isBoolean()) {
            output.writeAscii(node.booleanValue() ? "true" : "false");
        } else if (node.isNull()) {
            output.writeAscii("null");
        } else {
            throw new IllegalArgumentException("Workflow source contains a non-JSON value");
        }
    }

    private void writeObject(ObjectNode node, BoundedCanonicalOutput output) {
        output.writeByte('{');
        boolean first = true;
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!first) {
                output.writeByte(',');
            }
            first = false;
            output.writeQuoted(field.getKey());
            output.writeByte(':');
            writeNode(field.getValue(), output);
        }
        output.writeByte('}');
    }

    private void writeArray(ArrayNode node, BoundedCanonicalOutput output) {
        output.writeByte('[');
        for (int index = 0; index < node.size(); index++) {
            if (index > 0) {
                output.writeByte(',');
            }
            writeNode(node.get(index), output);
        }
        output.writeByte(']');
    }

    record CanonicalDocument(ObjectNode root, String json, int byteLength) {}

    static final class CanonicalOutputTooLargeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CanonicalOutputTooLargeException() {
            super("Canonical workflow JSON exceeds its configured byte limit");
        }
    }

    private static final class BoundedCanonicalOutput {

        private static final int MINIMUM_OUTPUT_BYTES = 1;
        private static final byte[] ASCII_ZEROES = new byte[1024];

        static {
            Arrays.fill(ASCII_ZEROES, (byte) '0');
        }

        private final int maximumBytes;
        private final ByteArrayOutputStream output;

        BoundedCanonicalOutput(int maximumBytes) {
            if (maximumBytes < MINIMUM_OUTPUT_BYTES) {
                throw new IllegalArgumentException("maximumBytes must be positive");
            }
            this.maximumBytes = maximumBytes;
            output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        void writeByte(int value) {
            reserve(1);
            output.write(value);
        }

        void writeAscii(String value) {
            write(value.getBytes(StandardCharsets.US_ASCII));
        }

        void writeQuoted(String value) {
            byte[] encoded = JsonStringEncoder.getInstance().quoteAsUTF8(value);
            reserve((long) encoded.length + 2);
            output.write('"');
            output.write(encoded, 0, encoded.length);
            output.write('"');
        }

        void writePlainDecimal(BigDecimal value) {
            BigDecimal normalized = value.stripTrailingZeros();
            if (normalized.signum() == 0) {
                writeByte('0');
                return;
            }
            String digits = normalized.unscaledValue().abs().toString();
            int scale = normalized.scale();
            int signLength = normalized.signum() < 0 ? 1 : 0;
            long zeroes;
            long totalLength;
            if (scale <= 0) {
                zeroes = -(long) scale;
                totalLength = signLength + digits.length() + zeroes;
            } else if (digits.length() > scale) {
                zeroes = 0;
                totalLength = signLength + digits.length() + 1L;
            } else {
                zeroes = (long) scale - digits.length();
                totalLength = signLength + 2L + zeroes + digits.length();
            }
            reserve(totalLength);
            if (normalized.signum() < 0) {
                output.write('-');
            }
            if (scale <= 0) {
                writeReservedAscii(digits);
                writeReservedZeroes(zeroes);
            } else if (digits.length() > scale) {
                int decimalPoint = digits.length() - scale;
                writeReservedAscii(digits.substring(0, decimalPoint));
                output.write('.');
                writeReservedAscii(digits.substring(decimalPoint));
            } else {
                output.write('0');
                output.write('.');
                writeReservedZeroes(zeroes);
                writeReservedAscii(digits);
            }
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }

        private void write(byte[] value) {
            reserve(value.length);
            output.write(value, 0, value.length);
        }

        private void writeReservedAscii(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            output.write(bytes, 0, bytes.length);
        }

        private void writeReservedZeroes(long count) {
            long remaining = count;
            while (remaining > 0) {
                int chunk = (int) Math.min(remaining, ASCII_ZEROES.length);
                output.write(ASCII_ZEROES, 0, chunk);
                remaining -= chunk;
            }
        }

        private void reserve(long additionalBytes) {
            if (additionalBytes < 0 || additionalBytes > maximumBytes - output.size()) {
                throw new CanonicalOutputTooLargeException();
            }
        }
    }
}
