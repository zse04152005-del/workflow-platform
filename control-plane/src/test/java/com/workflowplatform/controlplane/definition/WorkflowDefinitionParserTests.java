package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workflowplatform.contracts.dsl.ParameterMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionParserTests {

    private static final String SECRET_MARKER = "never-log-this-payload";
    private static final String MINIMAL_JSON =
            """
            {
              "schemaVersion": 1,
              "name": "parser-test",
              "catalogVersion": 1,
              "triggers": [{"id": "manual", "type": "manual"}],
              "nodes": [
                {"id": "start", "type": "start", "executorVersion": 1},
                {"id": "end", "type": "end", "executorVersion": 1}
              ],
              "edges": [
                {"id": "edge", "from": "start", "to": "end", "on": "success"}
              ]
            }
            """;

    private final WorkflowDefinitionParser parser = new JacksonWorkflowDefinitionParser();

    @Test
    void producesIdenticalCanonicalJsonAndChecksumForEquivalentJsonAndYaml() throws IOException {
        ParsedWorkflowDefinition json =
                parseFixture("equivalent/workflow.json", WorkflowDefinitionSourceFormat.JSON);
        ParsedWorkflowDefinition yaml =
                parseFixture("equivalent/workflow.yaml", WorkflowDefinitionSourceFormat.YAML);

        assertEquals(
                json.canonicalJson(),
                yaml.canonicalJson(),
                "Equivalent JSON and YAML must have identical canonical output");
        assertEquals(
                json.checksum(),
                yaml.checksum(),
                "Equivalent JSON and YAML must have identical checksums");
        assertEquals(
                WorkflowDefinitionSourceFormat.JSON,
                json.sourceFormat(),
                "JSON source format must be retained");
        assertEquals(
                WorkflowDefinitionSourceFormat.YAML,
                yaml.sourceFormat(),
                "YAML source format must be retained");
        assertEquals(
                "parser-equivalence",
                json.definition().name(),
                "Core workflow name must be mapped");
        assertEquals(
                "yes",
                json.definition().triggers().getFirst().configuration().get("enabled"),
                "JSON boolean-like string must remain textual");
        assertEquals(
                "off",
                yaml.definition().triggers().getFirst().configuration().get("disabled"),
                "YAML boolean-like word must be parsed as text");
    }

    @Test
    void canonicalizesObjectKeysNumbersAndMappingsDeterministically() throws IOException {
        ParsedWorkflowDefinition parsed =
                parseFixture("equivalent/workflow.json", WorkflowDefinitionSourceFormat.JSON);

        assertTrue(
                parsed.canonicalJson().startsWith("{\"catalogVersion\":1,\"edges\":"),
                "Canonical root keys must use natural String order");
        assertTrue(
                parsed.canonicalJson()
                        .contains("\"config\":{\"alpha\":0,\"array\":[2,1],\"zeta\":1}"),
                "Nested keys, negative zero and trailing zeros must normalize");
        assertTrue(
                parsed.canonicalJson()
                        .contains("\"mapping\":{\"alpha\":\"input.a\",\"zeta\":\"input.z\"}"),
                "Mapping object keys must be sorted canonically");
        assertEquals(
                List.of("alpha", "zeta"),
                parsed.definition().edges().getFirst().mapping().stream()
                        .map(ParameterMapping::target)
                        .toList(),
                "Mapped parameter list must follow canonical key order");
    }

    @Test
    void preservesArrayOrderAndDoesNotNormalizeUnicode() {
        ParsedWorkflowDefinition ordered =
                parseJson(withRootField(MINIMAL_JSON, "\"extension\": {\"values\": [2, 1]}"));
        ParsedWorkflowDefinition composed =
                parseJson(
                        MINIMAL_JSON.replace(
                                "\"parser-test\"", "\"parser-test\", \"description\": \"é\""));
        ParsedWorkflowDefinition decomposed =
                parseJson(
                        MINIMAL_JSON.replace(
                                "\"parser-test\"",
                                "\"parser-test\", \"description\": \"e\\u0301\""));

        assertTrue(
                ordered.canonicalJson().contains("\"values\":[2,1]"),
                "Array order must remain unchanged");
        assertNotEquals(
                composed.canonicalJson(),
                decomposed.canonicalJson(),
                "Canonicalization must not normalize Unicode code points");
        assertNotEquals(
                composed.checksum(),
                decomposed.checksum(),
                "Distinct Unicode forms must have distinct checksums");
    }

    @Test
    void acceptsOneUtf8BomAndCountsOriginalSourceBytes() {
        byte[] body = MINIMAL_JSON.getBytes(StandardCharsets.UTF_8);
        byte[] source = new byte[body.length + 3];
        source[0] = (byte) 0xEF;
        source[1] = (byte) 0xBB;
        source[2] = (byte) 0xBF;
        System.arraycopy(body, 0, source, 3, body.length);

        ParsedWorkflowDefinition parsed = parser.parse(source, WorkflowDefinitionSourceFormat.JSON);

        assertEquals(
                source.length,
                parsed.sourceByteLength(),
                "Original byte count must include the UTF-8 BOM");
        assertEquals(
                "parser-test", parsed.definition().name(), "BOM must not alter parsed content");
    }

    @Test
    void exposesDefensiveCanonicalBytesAndLowercaseSha256() {
        ParsedWorkflowDefinition parsed = parseJson(MINIMAL_JSON);
        byte[] first = parsed.canonicalBytes();
        byte[] expected = parsed.canonicalJson().getBytes(StandardCharsets.UTF_8);

        first[0] = (byte) 'x';

        assertArrayEquals(
                expected, parsed.canonicalBytes(), "canonicalBytes must return a defensive copy");
        assertEquals(
                sha256(expected), parsed.checksum(), "Checksum must be SHA-256 of canonical bytes");
        assertTrue(
                parsed.checksum().matches("^[0-9a-f]{64}$"),
                "Checksum must be lowercase 64-character hexadecimal");
    }

    @Test
    void requiresTheCallerToSelectTheSourceFormat() {
        String yaml =
                """
                schemaVersion: 1
                name: parser-test
                catalogVersion: 1
                triggers: [{id: manual, type: manual}]
                nodes:
                  - {id: start, type: start, executorVersion: 1}
                  - {id: end, type: end, executorVersion: 1}
                edges: [{id: edge, from: start, to: end, on: success}]
                """;

        assertFailure(
                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT,
                yaml,
                WorkflowDefinitionSourceFormat.JSON);
        assertEquals(
                "json",
                WorkflowDefinitionSourceFormat.JSON.wireName(),
                "JSON wire name must be stable");
        assertEquals(
                "yaml",
                WorkflowDefinitionSourceFormat.YAML.wireName(),
                "YAML wire name must be stable");
    }

    @Test
    void rejectsNullApiInputs() {
        assertThrows(
                NullPointerException.class,
                () -> parser.parse(null, WorkflowDefinitionSourceFormat.JSON),
                "Null source bytes must be rejected");
        assertThrows(
                NullPointerException.class,
                () -> parser.parse(new byte[] {1}, null),
                "Null source format must be rejected");
    }

    @Test
    void rejectsEmptyWhitespaceOversizedAndInvalidUtf8Input() {
        assertFailure(
                WorkflowDefinitionParseFailure.EMPTY_INPUT,
                new byte[0],
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.EMPTY_INPUT,
                " \r\n\t",
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.INPUT_TOO_LARGE,
                "x".repeat(JacksonWorkflowDefinitionParser.MAXIMUM_INPUT_BYTES + 1),
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.INVALID_UTF8,
                new byte[] {(byte) 0xC3, (byte) 0x28},
                WorkflowDefinitionSourceFormat.JSON);
    }

    @Test
    void rejectsDuplicateJsonAndYamlKeys() {
        String duplicateJson =
                MINIMAL_JSON.replace(
                        "\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1");
        String duplicateYaml =
                """
                schemaVersion: 1
                schemaVersion: 1
                name: parser-test
                """;

        assertFailure(
                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT,
                duplicateJson,
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT,
                duplicateYaml,
                WorkflowDefinitionSourceFormat.YAML);
    }

    @Test
    void rejectsMultipleJsonAndYamlDocuments() {
        assertFailure(
                WorkflowDefinitionParseFailure.MULTIPLE_DOCUMENTS,
                MINIMAL_JSON + MINIMAL_JSON,
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.MULTIPLE_DOCUMENTS,
                MINIMAL_JSON + "\n---\n" + MINIMAL_JSON,
                WorkflowDefinitionSourceFormat.YAML);
    }

    @Test
    void rejectsNonObjectRoots() {
        assertFailure(
                WorkflowDefinitionParseFailure.ROOT_NOT_OBJECT,
                "[]",
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.ROOT_NOT_OBJECT,
                "- item\n",
                WorkflowDefinitionSourceFormat.YAML);
    }

    @Test
    void rejectsDocumentsBeyondTheNestingLimit() {
        String nested =
                "[".repeat(JacksonWorkflowDefinitionParser.MAXIMUM_NESTING_DEPTH + 1)
                        + "0"
                        + "]".repeat(JacksonWorkflowDefinitionParser.MAXIMUM_NESTING_DEPTH + 1);
        String source = withRootField(MINIMAL_JSON, "\"extension\": " + nested);

        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                source,
                WorkflowDefinitionSourceFormat.JSON);
    }

    @Test
    void rejectsStringsNumbersAndFieldNamesBeyondTheirLimits() {
        String longString = "x".repeat(JacksonWorkflowDefinitionParser.MAXIMUM_STRING_LENGTH + 1);
        String longNumber = "1".repeat(JacksonWorkflowDefinitionParser.MAXIMUM_NUMBER_LENGTH + 1);
        String longName = "n".repeat(JacksonWorkflowDefinitionParser.MAXIMUM_NAME_LENGTH + 1);

        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                withRootField(MINIMAL_JSON, "\"extension\": \"" + longString + "\""),
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                withRootField(MINIMAL_JSON, "\"extension\": " + longNumber),
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                withRootField(MINIMAL_JSON, "\"" + longName + "\": 1"),
                WorkflowDefinitionSourceFormat.JSON);
    }

    @Test
    void rejectsDocumentsBeyondTheTokenLimit() {
        String values =
                String.join(
                        ",",
                        Collections.nCopies(
                                JacksonWorkflowDefinitionParser.MAXIMUM_TOKEN_COUNT + 1, "0"));
        String source = withRootField(MINIMAL_JSON, "\"extension\": [" + values + "]");

        assertTrue(
                source.getBytes(StandardCharsets.UTF_8).length
                        < JacksonWorkflowDefinitionParser.MAXIMUM_INPUT_BYTES,
                "Token-limit fixture must remain below the byte limit");
        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                source,
                WorkflowDefinitionSourceFormat.JSON);
    }

    @Test
    void rejectsYamlCollectionAliasesRecursiveKeysAndCustomTags() {
        String alias =
                minimalYaml()
                        + """
                        extension: &shared
                          - value
                        copy: *shared
                        """;
        String recursive =
                minimalYaml()
                        + """
                        extension: &loop
                          ? *loop
                          : value
                        """;
        String customTag = minimalYaml() + "extension: !workflow/unsafe value\n";

        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                alias,
                WorkflowDefinitionSourceFormat.YAML);
        assertFailure(
                WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED,
                recursive,
                WorkflowDefinitionSourceFormat.YAML);
        assertFailure(
                WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT,
                customTag,
                WorkflowDefinitionSourceFormat.YAML);
    }

    @Test
    void mapsMissingFieldsUnknownDiscriminatorsAndInvalidDurationsToCoreModelFailure() {
        String missingSchema = MINIMAL_JSON.replace("\"schemaVersion\": 1,", "");
        String unknownNode = MINIMAL_JSON.replace("\"type\": \"start\"", "\"type\": \"script\"");
        String invalidDuration =
                MINIMAL_JSON.replace(
                        "\"type\": \"start\", \"executorVersion\": 1",
                        "\"type\": \"http\", \"executorVersion\": 1, \"timeout\": \"tomorrow\"");

        assertFailure(
                WorkflowDefinitionParseFailure.CORE_MODEL_INVALID,
                missingSchema,
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.CORE_MODEL_INVALID,
                unknownNode,
                WorkflowDefinitionSourceFormat.JSON);
        assertFailure(
                WorkflowDefinitionParseFailure.CORE_MODEL_INVALID,
                invalidDuration,
                WorkflowDefinitionSourceFormat.JSON);
    }

    @Test
    void rejectsCanonicalOutputBeyondTheByteLimit() {
        String exponentNumbers = String.join(",", Collections.nCopies(106, "1e9999"));
        String source = withRootField(MINIMAL_JSON, "\"extension\": [" + exponentNumbers + "]");

        assertTrue(
                source.getBytes(StandardCharsets.UTF_8).length < 10_000,
                "Exponent fixture must demonstrate canonical size amplification");
        assertFailure(
                WorkflowDefinitionParseFailure.CANONICAL_OUTPUT_TOO_LARGE,
                source,
                WorkflowDefinitionSourceFormat.JSON);
    }

    @Test
    void expandsLargeScientificNotationWithoutFloatingPointLoss() {
        String source = withRootField(MINIMAL_JSON, "\"extension\": 1e99999");
        String expectedNumber = "1" + "0".repeat(99_999);

        ParsedWorkflowDefinition parsed = parseJson(source);

        assertTrue(
                parsed.canonicalJson().contains("\"extension\":" + expectedNumber),
                "Large exponent must expand to an exact plain decimal");
        assertEquals(
                sha256(parsed.canonicalBytes()),
                parsed.checksum(),
                "Expanded arbitrary-precision number must participate in checksum");
    }

    @Test
    void keepsPublicFailureMessagesStableAndPayloadSafe() {
        String source = "{\"" + SECRET_MARKER + "\": }";

        WorkflowDefinitionParseException exception =
                assertFailure(
                        WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT,
                        source,
                        WorkflowDefinitionSourceFormat.JSON);

        assertEquals(
                "Workflow definition source rejected: malformed_document",
                exception.getMessage(),
                "Public parser message must contain only the stable failure code");
        assertFalse(
                exception.getMessage().contains(SECRET_MARKER),
                "Public parser message must not expose source payload");
        assertTrue(exception.line() > 0, "Malformed JSON should expose a one-based line");
        assertTrue(exception.column() > 0, "Malformed JSON should expose a one-based column");
    }

    @Test
    void leavesGraphSemanticsForG303() {
        String source =
                """
                {
                  "schemaVersion": 1,
                  "name": "graph-boundary",
                  "catalogVersion": 1,
                  "triggers": [{"id": "manual", "type": "manual"}],
                  "nodes": [
                    {"id": "start", "type": "start", "executorVersion": 1},
                    {"id": "start", "type": "start", "executorVersion": 1}
                  ],
                  "edges": [
                    {"id": "edge", "from": "missing_from", "to": "missing_to", "on": "success"}
                  ]
                }
                """;

        ParsedWorkflowDefinition parsed = parseJson(source);

        assertEquals(
                2,
                parsed.definition().nodes().size(),
                "G3-02 must not reject duplicate Start nodes");
        assertEquals(
                "missing_from",
                parsed.definition().edges().getFirst().from(),
                "G3-02 must preserve dangling edge references for G3-03 validation");
    }

    @Test
    void exposesAllStableFailureWireNames() {
        assertEquals(
                List.of(
                        "empty_input",
                        "input_too_large",
                        "invalid_utf8",
                        "malformed_document",
                        "resource_limit_exceeded",
                        "multiple_documents",
                        "root_not_object",
                        "core_model_invalid",
                        "canonical_output_too_large"),
                List.of(WorkflowDefinitionParseFailure.values()).stream()
                        .map(WorkflowDefinitionParseFailure::wireName)
                        .toList(),
                "Parser failure wire names must remain stable and ordered");
    }

    private ParsedWorkflowDefinition parseFixture(
            String relative, WorkflowDefinitionSourceFormat format) throws IOException {
        Path fixture = repositoryRoot().resolve("tests/contracts/dsl/parser").resolve(relative);
        assertTrue(Files.isRegularFile(fixture), "Missing parser fixture: " + fixture);
        return parser.parse(Files.readAllBytes(fixture), format);
    }

    private ParsedWorkflowDefinition parseJson(String source) {
        return parser.parse(
                source.getBytes(StandardCharsets.UTF_8), WorkflowDefinitionSourceFormat.JSON);
    }

    private WorkflowDefinitionParseException assertFailure(
            WorkflowDefinitionParseFailure expected,
            String source,
            WorkflowDefinitionSourceFormat format) {
        return assertFailure(expected, source.getBytes(StandardCharsets.UTF_8), format);
    }

    private WorkflowDefinitionParseException assertFailure(
            WorkflowDefinitionParseFailure expected,
            byte[] source,
            WorkflowDefinitionSourceFormat format) {
        WorkflowDefinitionParseException exception =
                assertThrows(
                        WorkflowDefinitionParseException.class,
                        () -> parser.parse(source, format),
                        "Parser must reject the invalid source with a stable failure");
        assertEquals(expected, exception.failure(), "Parser failure reason must match");
        assertEquals(
                "Workflow definition source rejected: " + expected.wireName(),
                exception.getMessage(),
                "Parser failure message must be payload-safe");
        assertNotNull(exception.failure(), "Parser failure reason must never be null");
        return exception;
    }

    private static String withRootField(String source, String field) {
        int closingBrace = source.lastIndexOf('}');
        return source.substring(0, closingBrace)
                + ",\n"
                + field
                + "\n"
                + source.substring(closingBrace);
    }

    private static String minimalYaml() {
        return """
                schemaVersion: 1
                name: parser-test
                catalogVersion: 1
                triggers: [{id: manual, type: manual}]
                nodes:
                  - {id: start, type: start, executorVersion: 1}
                  - {id: end, type: end, executorVersion: 1}
                edges: [{id: edge, from: start, to: end, on: success}]
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

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
