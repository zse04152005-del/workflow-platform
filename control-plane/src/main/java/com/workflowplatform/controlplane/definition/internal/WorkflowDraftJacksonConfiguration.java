package com.workflowplatform.controlplane.definition.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Applies the G3-02 parser bounds before MVC materializes request JsonNode values. */
@Configuration(proxyBeanMethods = false)
class WorkflowDraftJacksonConfiguration {

    private static final int MAXIMUM_HTTP_DOCUMENT_CHARACTERS = 2_200_000;
    private static final int MAXIMUM_NESTING_DEPTH = 64;
    private static final int MAXIMUM_TOKEN_COUNT = 200_000;
    private static final int MAXIMUM_NUMBER_LENGTH = 100;
    private static final int MAXIMUM_STRING_LENGTH = 262_144;
    private static final int MAXIMUM_NAME_LENGTH = 200;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer boundedWorkflowJsonRequests() {
        StreamReadConstraints constraints =
                StreamReadConstraints.builder()
                        .maxNestingDepth(MAXIMUM_NESTING_DEPTH)
                        .maxDocumentLength(MAXIMUM_HTTP_DOCUMENT_CHARACTERS)
                        .maxTokenCount(MAXIMUM_TOKEN_COUNT)
                        .maxNumberLength(MAXIMUM_NUMBER_LENGTH)
                        .maxStringLength(MAXIMUM_STRING_LENGTH)
                        .maxNameLength(MAXIMUM_NAME_LENGTH)
                        .build();
        return builder ->
                builder.postConfigurer(
                        mapper -> {
                            mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                            mapper.getFactory().setStreamReadConstraints(constraints);
                        });
    }
}
