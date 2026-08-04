package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.inspector.UnTrustedTagInspector;

/** Jackson/SnakeYAML implementation of the bounded deterministic definition parser. */
@Component
final class JacksonWorkflowDefinitionParser implements WorkflowDefinitionParser {

    static final int MAXIMUM_INPUT_BYTES = 1_048_576;
    static final int MAXIMUM_CANONICAL_BYTES = 1_048_576;
    static final int MAXIMUM_NESTING_DEPTH = 64;
    static final int MAXIMUM_DOCUMENT_CHARACTERS = 1_048_576;
    static final int MAXIMUM_TOKEN_COUNT = 200_000;
    static final int MAXIMUM_NUMBER_LENGTH = 100;
    static final int MAXIMUM_STRING_LENGTH = 262_144;
    static final int MAXIMUM_NAME_LENGTH = 200;

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private static final StreamReadConstraints STREAM_CONSTRAINTS =
            StreamReadConstraints.builder()
                    .maxNestingDepth(MAXIMUM_NESTING_DEPTH)
                    .maxDocumentLength(MAXIMUM_DOCUMENT_CHARACTERS)
                    .maxTokenCount(MAXIMUM_TOKEN_COUNT)
                    .maxNumberLength(MAXIMUM_NUMBER_LENGTH)
                    .maxStringLength(MAXIMUM_STRING_LENGTH)
                    .maxNameLength(MAXIMUM_NAME_LENGTH)
                    .build();

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final Yaml yamlValidator;
    private final WorkflowDefinitionCanonicalizer canonicalizer;
    private final WorkflowDefinitionModelMapper modelMapper;

    JacksonWorkflowDefinitionParser() {
        jsonMapper = configuredMapper(jsonFactory());
        yamlMapper = configuredMapper(yamlFactory());
        yamlValidator = new Yaml(new SafeConstructor(yamlLoaderOptions()));
        ObjectMapper canonicalMapper = configuredMapper(JsonFactory.builder().build());
        canonicalizer = new WorkflowDefinitionCanonicalizer();
        modelMapper = new WorkflowDefinitionModelMapper(canonicalMapper);
    }

    @Override
    public ParsedWorkflowDefinition parse(byte[] source, WorkflowDefinitionSourceFormat format) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        if (source.length == 0) {
            throw failure(WorkflowDefinitionParseFailure.EMPTY_INPUT);
        }
        if (source.length > MAXIMUM_INPUT_BYTES) {
            throw failure(WorkflowDefinitionParseFailure.INPUT_TOO_LARGE);
        }
        String text = decodeUtf8(source);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw failure(WorkflowDefinitionParseFailure.EMPTY_INPUT);
        }

        if (format == WorkflowDefinitionSourceFormat.YAML) {
            validateYaml(text);
        }
        ObjectNode sourceRoot = readSingleObject(text, mapperFor(format));
        WorkflowDefinitionCanonicalizer.CanonicalDocument canonical;
        try {
            canonical = canonicalizer.canonicalize(sourceRoot, MAXIMUM_CANONICAL_BYTES);
        } catch (WorkflowDefinitionCanonicalizer.CanonicalOutputTooLargeException exception) {
            throw failure(WorkflowDefinitionParseFailure.CANONICAL_OUTPUT_TOO_LARGE, exception);
        } catch (IllegalArgumentException exception) {
            throw failure(WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT, exception);
        }
        if (canonical.byteLength() > MAXIMUM_CANONICAL_BYTES) {
            throw failure(WorkflowDefinitionParseFailure.CANONICAL_OUTPUT_TOO_LARGE);
        }

        WorkflowDefinition definition;
        try {
            definition = modelMapper.map(canonical.root());
        } catch (RuntimeException exception) {
            throw failure(WorkflowDefinitionParseFailure.CORE_MODEL_INVALID, exception);
        }
        return new ParsedWorkflowDefinition(format, source.length, definition, canonical.json());
    }

    private ObjectMapper mapperFor(WorkflowDefinitionSourceFormat format) {
        return switch (format) {
            case JSON -> jsonMapper;
            case YAML -> yamlMapper;
        };
    }

    private ObjectNode readSingleObject(String text, ObjectMapper mapper) {
        try (JsonParser sourceParser = mapper.createParser(text)) {
            if (sourceParser.nextToken() == null) {
                throw failure(WorkflowDefinitionParseFailure.EMPTY_INPUT);
            }
            JsonNode root = mapper.readTree(sourceParser);
            if (sourceParser.nextToken() != null) {
                throw failure(WorkflowDefinitionParseFailure.MULTIPLE_DOCUMENTS);
            }
            if (!(root instanceof ObjectNode object)) {
                throw failure(WorkflowDefinitionParseFailure.ROOT_NOT_OBJECT);
            }
            return object;
        } catch (WorkflowDefinitionParseException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            WorkflowDefinitionParseFailure reason =
                    containsStreamConstraint(exception)
                            ? WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED
                            : WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT;
            JsonLocation location = exception.getLocation();
            int line = location == null ? 0 : positiveLocation(location.getLineNr());
            int column = location == null ? 0 : positiveLocation(location.getColumnNr());
            throw new WorkflowDefinitionParseException(reason, line, column, exception);
        } catch (IOException exception) {
            throw failure(WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT, exception);
        }
    }

    private void validateYaml(String text) {
        try {
            Iterator<Object> documents = yamlValidator.loadAll(text).iterator();
            if (!documents.hasNext()) {
                throw failure(WorkflowDefinitionParseFailure.EMPTY_INPUT);
            }
            documents.next();
            if (documents.hasNext()) {
                throw failure(WorkflowDefinitionParseFailure.MULTIPLE_DOCUMENTS);
            }
        } catch (WorkflowDefinitionParseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            WorkflowDefinitionParseFailure reason =
                    isYamlResourceConstraint(exception)
                            ? WorkflowDefinitionParseFailure.RESOURCE_LIMIT_EXCEEDED
                            : WorkflowDefinitionParseFailure.MALFORMED_DOCUMENT;
            MarkedYAMLException marked = findCause(exception, MarkedYAMLException.class);
            int line = marked == null ? 0 : marked.getProblemMark().getLine() + 1;
            int column = marked == null ? 0 : marked.getProblemMark().getColumn() + 1;
            throw new WorkflowDefinitionParseException(reason, line, column, exception);
        }
    }

    private String decodeUtf8(byte[] source) {
        try {
            CharBuffer decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(source));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw failure(WorkflowDefinitionParseFailure.INVALID_UTF8, exception);
        }
    }

    private static ObjectMapper configuredMapper(JsonFactory factory) {
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return mapper;
    }

    private static JsonFactory jsonFactory() {
        return JsonFactory.builder()
                .streamReadConstraints(STREAM_CONSTRAINTS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    private static YAMLFactory yamlFactory() {
        return YAMLFactory.builder()
                .loaderOptions(yamlLoaderOptions())
                .streamReadConstraints(STREAM_CONSTRAINTS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
                .build();
    }

    private static LoaderOptions yamlLoaderOptions() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setMaxAliasesForCollections(0);
        loaderOptions.setAllowRecursiveKeys(false);
        loaderOptions.setMergeOnCompose(false);
        loaderOptions.setTagInspector(new UnTrustedTagInspector());
        loaderOptions.setCodePointLimit(MAXIMUM_DOCUMENT_CHARACTERS);
        loaderOptions.setNestingDepthLimit(MAXIMUM_NESTING_DEPTH);
        return loaderOptions;
    }

    private static boolean containsStreamConstraint(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof StreamConstraintsException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isYamlResourceConstraint(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("aliases for non-scalar nodes exceeds")
                            || message.contains("code point limit")
                            || message.contains("Nesting Depth"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static int positiveLocation(int value) {
        return Math.max(value, 0);
    }

    private static WorkflowDefinitionParseException failure(WorkflowDefinitionParseFailure reason) {
        return new WorkflowDefinitionParseException(reason, 0, 0, null);
    }

    private static WorkflowDefinitionParseException failure(
            WorkflowDefinitionParseFailure reason, Throwable cause) {
        return new WorkflowDefinitionParseException(reason, 0, 0, cause);
    }
}
