package com.workflowplatform.controlplane.definition.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class WorkflowDraftJacksonConfigurationTests {

    private final ObjectMapper mapper = configuredMapper();

    @Test
    void rejectsDuplicateKeysBeforeTheyCanBeCollapsedIntoAJsonNode() {
        assertThrows(
                JsonProcessingException.class,
                () -> mapper.readTree("{\"definition\":{\"name\":\"first\",\"name\":\"last\"}}"));
    }

    @Test
    void rejectsDocumentsBeyondTheConfiguredNestingDepth() {
        String document = "[".repeat(65) + "0" + "]".repeat(65);
        assertThrows(JsonProcessingException.class, () -> mapper.readTree(document));
    }

    private static ObjectMapper configuredMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new WorkflowDraftJacksonConfiguration().boundedWorkflowJsonRequests().customize(builder);
        return builder.build();
    }
}
