package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.controlplane.api.error.ApiProblemException;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// G3-09 service coverage: bundle integrity, mapping, idempotency and bounded differences.
@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.AvoidDuplicateLiterals"})
class WorkflowPortabilityServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID SOURCE_WORKFLOW_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c495");
    private static final UUID SOURCE_PROJECT_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c496");
    private static final UUID TARGET_PROJECT_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c497");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "g3-09-service-actor");
    private static final Instant CAPTURED_AT = Instant.parse("2026-07-31T06:00:00Z");
    private static final String SOURCE_DEFINITION =
            """
            {
              "schemaVersion": 1,
              "name": "portable-source",
              "catalogVersion": 1,
              "triggers": [
                {"id":"webhook","type":"http","config":{"authentication":"hmac","hmacSecretRef":"source.signing"}}
              ],
              "nodes": [
                {"id":"start","type":"start","executorVersion":1},
                {"id":"call","type":"http","executorVersion":1,"config":{
                  "connectorRef":"source.connector","method":"POST",
                  "secretRefs":["source.api_key"]
                }},
                {"id":"end","type":"end","executorVersion":1}
              ],
              "edges": [
                {"id":"start_call","from":"start","to":"call","on":"success"},
                {"id":"call_end","from":"call","to":"end","on":"success"}
              ]
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WorkflowDefinitionParser parser = new JacksonWorkflowDefinitionParser();
    private WorkflowPortabilityStore store;
    private WorkflowPortabilityService service;
    private WorkflowSnapshot draftSnapshot;

    @BeforeEach
    void setUp() {
        store = mock(WorkflowPortabilityStore.class);
        service = new WorkflowPortabilityService(parser, store, objectMapper);
        ParsedWorkflowDefinition parsed = parse(SOURCE_DEFINITION);
        draftSnapshot =
                new WorkflowSnapshot(
                        SOURCE_WORKFLOW_ID,
                        SOURCE_PROJECT_ID,
                        "portable-source",
                        "portable description",
                        WorkflowSnapshot.DRAFT,
                        4L,
                        null,
                        parsed.canonicalJson(),
                        parsed.checksum(),
                        CAPTURED_AT);
        when(store.create(any())).thenAnswer(invocation -> created(invocation.getArgument(0)));
    }

    @Test
    void exportsAnIntegrityBoundSortedSecretFreeResourceManifest() {
        when(store.load(CONTEXT, SOURCE_WORKFLOW_ID, null)).thenReturn(draftSnapshot);

        WorkflowExportBundle bundle = service.export(CONTEXT, SOURCE_WORKFLOW_ID, null);

        assertEquals(WorkflowExportBundle.FORMAT, bundle.format());
        assertEquals(1, bundle.formatVersion());
        assertEquals("draft", bundle.source().kind());
        assertEquals(4L, bundle.source().draftRevision());
        assertEquals(draftSnapshot.checksum(), bundle.source().checksum());
        assertEquals(
                List.of(
                        new WorkflowExportBundle.ResourceReference("connector", "source.connector"),
                        new WorkflowExportBundle.ResourceReference("secret", "source.api_key"),
                        new WorkflowExportBundle.ResourceReference("secret", "source.signing")),
                bundle.resources());
        assertFalse(objectMapper.valueToTree(bundle).has("tenantId"));
    }

    @Test
    void clonesWithinOneProjectWithIdentityReferencesAndAuditedIdempotency() {
        when(store.load(CONTEXT, SOURCE_WORKFLOW_ID, null)).thenReturn(draftSnapshot);

        WorkflowDraft created =
                service.cloneWorkflow(
                        CONTEXT,
                        SOURCE_WORKFLOW_ID,
                        SOURCE_PROJECT_ID,
                        "portable-copy",
                        null,
                        null,
                        List.of(),
                        "clone-key-1001");

        assertEquals("portable-copy", created.name());
        ArgumentCaptor<WorkflowPortabilityStore.CreateCommand> command =
                ArgumentCaptor.forClass(WorkflowPortabilityStore.CreateCommand.class);
        verify(store).create(command.capture());
        assertEquals("workflow.definition.clone", command.getValue().auditAction());
        assertEquals(
                "workflow:clone:" + SOURCE_WORKFLOW_ID,
                command.getValue().idempotency().operationScope());
        assertTrue(command.getValue().canonicalDefinition().contains("source.connector"));
        assertTrue(command.getValue().canonicalDefinition().contains("portable-copy"));
        assertEquals(64, command.getValue().idempotency().requestDigest().length());
    }

    @Test
    void requiresCompleteMappingsAcrossProjectsAndRewritesEveryKnownReference() {
        when(store.load(CONTEXT, SOURCE_WORKFLOW_ID, null)).thenReturn(draftSnapshot);
        assertThrows(
                ApiProblemException.class,
                () ->
                        service.cloneWorkflow(
                                CONTEXT,
                                SOURCE_WORKFLOW_ID,
                                TARGET_PROJECT_ID,
                                "mapped-copy",
                                null,
                                null,
                                List.of(),
                                "clone-key-1002"));

        service.cloneWorkflow(
                CONTEXT,
                SOURCE_WORKFLOW_ID,
                TARGET_PROJECT_ID,
                "mapped-copy",
                null,
                null,
                mappings(),
                "clone-key-1003");

        ArgumentCaptor<WorkflowPortabilityStore.CreateCommand> command =
                ArgumentCaptor.forClass(WorkflowPortabilityStore.CreateCommand.class);
        verify(store).create(command.capture());
        assertTrue(command.getValue().canonicalDefinition().contains("target.connector"));
        assertTrue(command.getValue().canonicalDefinition().contains("target.api_key"));
        assertTrue(command.getValue().canonicalDefinition().contains("target.signing"));
        assertFalse(command.getValue().canonicalDefinition().contains("source.connector"));
    }

    @Test
    void importsOnlyWhenChecksumManifestAndExplicitMappingsAreComplete() {
        when(store.load(CONTEXT, SOURCE_WORKFLOW_ID, null)).thenReturn(draftSnapshot);
        WorkflowExportBundle exported = service.export(CONTEXT, SOURCE_WORKFLOW_ID, null);
        WorkflowExportBundle tampered =
                new WorkflowExportBundle(
                        exported.format(),
                        exported.formatVersion(),
                        new WorkflowExportBundle.Source(
                                exported.source().workflowId(),
                                exported.source().kind(),
                                exported.source().draftRevision(),
                                exported.source().version(),
                                "f".repeat(64),
                                exported.source().capturedAt()),
                        exported.workflow(),
                        exported.resources());
        assertThrows(
                ApiProblemException.class,
                () ->
                        service.importWorkflow(
                                CONTEXT,
                                TARGET_PROJECT_ID,
                                "imported-copy",
                                null,
                                tampered,
                                mappings(),
                                "import-key-1001"));
        assertThrows(
                ApiProblemException.class,
                () ->
                        service.importWorkflow(
                                CONTEXT,
                                TARGET_PROJECT_ID,
                                "imported-copy",
                                null,
                                exported,
                                mappings().subList(0, 2),
                                "import-key-1002"));

        service.importWorkflow(
                CONTEXT,
                TARGET_PROJECT_ID,
                "imported-copy",
                "override",
                exported,
                mappings(),
                "import-key-1003");

        ArgumentCaptor<WorkflowPortabilityStore.CreateCommand> command =
                ArgumentCaptor.forClass(WorkflowPortabilityStore.CreateCommand.class);
        verify(store).create(command.capture());
        assertEquals("workflow.definition.import", command.getValue().auditAction());
        assertEquals("workflow:import", command.getValue().idempotency().operationScope());
        assertEquals("override", command.getValue().description());
        assertNotEquals(exported.source().checksum(), command.getValue().checksum());
    }

    @Test
    void producesDeterministicVersionDiffsAndCapsLargePublicChangeLists() throws Exception {
        WorkflowSnapshot versionOne = versionSnapshot(1, SOURCE_DEFINITION);
        String changedDefinition =
                SOURCE_DEFINITION.replace("\"method\":\"POST\"", "\"method\":\"GET\"");
        WorkflowSnapshot versionTwo = versionSnapshot(2, changedDefinition);
        when(store.load(CONTEXT, SOURCE_WORKFLOW_ID, 1)).thenReturn(versionOne);
        when(store.load(CONTEXT, SOURCE_WORKFLOW_ID, 2)).thenReturn(versionTwo);

        WorkflowVersionDiff diff = service.diff(CONTEXT, SOURCE_WORKFLOW_ID, 1, 2);

        assertEquals(1, diff.changeCount());
        assertEquals("/nodes/1/config/method", diff.changes().getFirst().path());
        assertEquals("replace", diff.changes().getFirst().operation());

        ObjectNode before = objectMapper.createObjectNode();
        ObjectNode after = objectMapper.createObjectNode();
        for (int index = 0; index < 1002; index++) {
            before.put("field" + index, index);
            after.put("field" + index, index + 1);
        }
        WorkflowVersionDiff capped =
                new WorkflowJsonDiff().compare(versionOne, before, versionTwo, after);
        assertEquals(1002, capped.changeCount());
        assertEquals(1000, capped.changes().size());
        assertTrue(capped.truncated());
        assertEquals("/field0", capped.changes().getFirst().path());
    }

    private WorkflowSnapshot versionSnapshot(int version, String definition) {
        ParsedWorkflowDefinition parsed = parse(definition);
        return new WorkflowSnapshot(
                SOURCE_WORKFLOW_ID,
                SOURCE_PROJECT_ID,
                "portable-source",
                null,
                WorkflowSnapshot.PUBLISHED_VERSION,
                null,
                version,
                parsed.canonicalJson(),
                parsed.checksum(),
                CAPTURED_AT.plusSeconds(version));
    }

    private ParsedWorkflowDefinition parse(String definition) {
        return parser.parse(
                definition.getBytes(StandardCharsets.UTF_8), WorkflowDefinitionSourceFormat.JSON);
    }

    private static List<WorkflowPortabilityService.ReferenceMapping> mappings() {
        return List.of(
                new WorkflowPortabilityService.ReferenceMapping(
                        "connector", "source.connector", "target.connector"),
                new WorkflowPortabilityService.ReferenceMapping(
                        "secret", "source.api_key", "target.api_key"),
                new WorkflowPortabilityService.ReferenceMapping(
                        "secret", "source.signing", "target.signing"));
    }

    private static WorkflowDraft created(WorkflowPortabilityStore.CreateCommand command) {
        return new WorkflowDraft(
                command.workflowId(),
                command.projectId(),
                command.name(),
                command.description(),
                "DRAFT",
                1,
                null,
                command.canonicalDefinition(),
                command.checksum(),
                CAPTURED_AT,
                CAPTURED_AT);
    }
}
