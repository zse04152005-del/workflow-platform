package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.workflowplatform.controlplane.definition.IntegrationResource.Kind;
import com.workflowplatform.controlplane.definition.IntegrationResource.Status;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.CronTriggerConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.DelayNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.EndNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.EndOutcome;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpAuthentication;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpMethod;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpTriggerConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HumanTaskNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.MisfirePolicy;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.ResourceScope;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class WorkflowConfigurationValidatorTests {

    private static final UUID TENANT_ID = UUID.fromString("018f0a00-0000-7000-8000-000000000001");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("018f0a00-0000-7000-8000-000000000002");
    private static final UUID PROJECT_ID = UUID.fromString("018f0a00-0000-7000-8000-000000000101");
    private static final UUID OTHER_PROJECT_ID =
            UUID.fromString("018f0a00-0000-7000-8000-000000000102");
    private static final UUID TENANT_CONNECTOR_ID =
            UUID.fromString("018f0a00-0000-7000-8000-000000001001");
    private static final UUID PROJECT_CONNECTOR_ID =
            UUID.fromString("018f0a00-0000-7000-8000-000000001002");
    private static final UUID HTTP_SECRET_ID =
            UUID.fromString("018f0a00-0000-7000-8000-000000002001");
    private static final UUID HMAC_SECRET_ID =
            UUID.fromString("018f0a00-0000-7000-8000-000000002002");

    private static final String CONNECTOR_REFERENCE = "service.primary";
    private static final String HTTP_SECRET_REFERENCE = "service.api_key";
    private static final String HMAC_SECRET_REFERENCE = "webhook.signing_key";

    private final WorkflowGraphValidator graphValidator = new DeterministicWorkflowGraphValidator();
    private final WorkflowConfigurationValidator validator =
            new DeterministicWorkflowConfigurationValidator();

    @Test
    void validatesEveryNodeAndTriggerVariantIntoTypedImmutableConfiguration() {
        ValidatedWorkflowConfiguration result = validate(validDefinition(), validContext());

        assertEquals(
                List.of("manual", "webhook", "nightly"), List.copyOf(result.triggers().keySet()));
        assertInstanceOf(
                ValidatedWorkflowConfiguration.ManualTriggerConfiguration.class,
                result.triggers().get("manual"));
        HttpTriggerConfiguration httpTrigger =
                assertInstanceOf(HttpTriggerConfiguration.class, result.triggers().get("webhook"));
        assertEquals(HttpAuthentication.HMAC, httpTrigger.authentication());
        assertEquals(HMAC_SECRET_ID, httpTrigger.hmacSecret().orElseThrow().id());
        assertEquals(ResourceScope.TENANT, httpTrigger.hmacSecret().orElseThrow().scope());
        CronTriggerConfiguration cron =
                assertInstanceOf(CronTriggerConfiguration.class, result.triggers().get("nightly"));
        assertEquals("0 0 * * *", cron.expression());
        assertEquals(ZoneId.of("Asia/Shanghai"), cron.timezone());
        assertEquals(MisfirePolicy.CATCH_UP_LIMITED, cron.misfirePolicy());
        assertEquals(5, cron.catchUpLimit());

        HttpNodeConfiguration http =
                assertInstanceOf(HttpNodeConfiguration.class, result.nodes().get("invoke"));
        assertEquals(PROJECT_CONNECTOR_ID, http.connector().id());
        assertEquals(ResourceScope.PROJECT, http.connector().scope());
        assertEquals(HttpMethod.POST, http.method());
        assertEquals("/v1/orders/{id}", http.pathTemplate().orElseThrow());
        assertEquals("input.trace_id", http.headers().get("X-Trace-Id"));
        assertEquals(HTTP_SECRET_ID, http.secrets().getFirst().id());
        assertEquals("input", http.bodyExpression().orElseThrow());
        assertEquals("response.body", http.resultExpression().orElseThrow());

        DelayNodeConfiguration delay =
                assertInstanceOf(DelayNodeConfiguration.class, result.nodes().get("wait"));
        assertEquals(Duration.ofMinutes(5), delay.duration().orElseThrow());
        assertTrue(delay.expiresAtExpression().isEmpty());
        HumanTaskNodeConfiguration human =
                assertInstanceOf(HumanTaskNodeConfiguration.class, result.nodes().get("review"));
        assertEquals("finance_reviewer", human.candidateRole().orElseThrow());
        assertEquals(Duration.ofHours(2), human.deadline().orElseThrow());
        assertEquals("object", human.formSchema().get("type"));
        EndNodeConfiguration end =
                assertInstanceOf(EndNodeConfiguration.class, result.nodes().get("end"));
        assertEquals(EndOutcome.FAILED, end.outcome());

        assertEquals(
                graphValidator.validate(validDefinition()).startNodeId(),
                result.graph().startNodeId());
        assertThrows(UnsupportedOperationException.class, () -> result.nodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.triggers().clear());
        assertThrows(UnsupportedOperationException.class, () -> http.headers().clear());
        assertThrows(UnsupportedOperationException.class, () -> http.secrets().clear());
        assertThrows(UnsupportedOperationException.class, () -> human.formSchema().clear());
    }

    @Test
    void supportsOidcTenantConnectorDelayExpressionAndHumanAssigneeDefaults() {
        List<WorkflowTrigger> triggers =
                List.of(
                        trigger("manual", TriggerType.MANUAL, Map.of()),
                        trigger(
                                "webhook",
                                TriggerType.HTTP,
                                Map.of("authentication", "oidc", "idempotencyRequired", true)),
                        trigger(
                                "nightly",
                                TriggerType.CRON,
                                Map.of(
                                        "expression",
                                        "0 0 * * *",
                                        "timezone",
                                        "Europe/Paris",
                                        "misfirePolicy",
                                        "skip")));
        List<WorkflowNode> nodes = validNodes();
        nodes.set(
                1,
                node(
                        "invoke",
                        NodeType.HTTP,
                        Map.of(
                                "config",
                                Map.of("connectorRef", CONNECTOR_REFERENCE, "method", "GET"))));
        nodes.set(
                3,
                node(
                        "wait",
                        NodeType.DELAY,
                        Map.of("config", Map.of("expiresAtExpression", "input.expires_at"))));
        nodes.set(
                4,
                node(
                        "review",
                        NodeType.HUMAN_TASK,
                        Map.of(
                                "config",
                                Map.of(
                                        "assigneeExpression",
                                        "input.owner",
                                        "formSchema",
                                        Map.of("type", "object"),
                                        "timeoutPolicy",
                                        "none"))));
        nodes.set(5, node("end", NodeType.END, Map.of()));
        WorkflowConfigurationValidationContext tenantOnly =
                new WorkflowConfigurationValidationContext(
                        TENANT_ID,
                        PROJECT_ID,
                        List.of(tenantConnector(), hmacSecret(), httpSecret()),
                        Set.of(
                                new ResourceUseGrant(Kind.CONNECTOR, TENANT_CONNECTOR_ID),
                                new ResourceUseGrant(Kind.SECRET, HTTP_SECRET_ID),
                                new ResourceUseGrant(Kind.SECRET, HMAC_SECRET_ID)));

        ValidatedWorkflowConfiguration result = validate(definition(triggers, nodes), tenantOnly);

        HttpTriggerConfiguration http = (HttpTriggerConfiguration) result.triggers().get("webhook");
        assertEquals(HttpAuthentication.OIDC, http.authentication());
        assertTrue(http.hmacSecret().isEmpty());
        assertEquals(
                ResourceScope.TENANT,
                ((HttpNodeConfiguration) result.nodes().get("invoke")).connector().scope());
        DelayNodeConfiguration delay = (DelayNodeConfiguration) result.nodes().get("wait");
        assertEquals("input.expires_at", delay.expiresAtExpression().orElseThrow());
        HumanTaskNodeConfiguration human =
                (HumanTaskNodeConfiguration) result.nodes().get("review");
        assertEquals("input.owner", human.assigneeExpression().orElseThrow());
        assertTrue(human.candidateRole().isEmpty());
        assertTrue(human.deadline().isEmpty());
        assertEquals(
                EndOutcome.SUCCEEDED, ((EndNodeConfiguration) result.nodes().get("end")).outcome());
    }

    @Test
    void rejectsTriggerSchemaAndParameterFailuresDeterministically() {
        assertTriggerFailure(
                trigger("manual", TriggerType.MANUAL, Map.of("unexpected", true)),
                WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID);
        assertTriggerFailure(
                trigger(
                        "webhook",
                        TriggerType.HTTP,
                        Map.of("authentication", "hmac", "idempotencyRequired", true)),
                WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID);
        assertTriggerFailure(
                trigger(
                        "webhook",
                        TriggerType.HTTP,
                        Map.of(
                                "authentication",
                                "oidc",
                                "idempotencyRequired",
                                true,
                                "hmacSecretRef",
                                HMAC_SECRET_REFERENCE)),
                WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID);
        assertTriggerFailure(
                trigger(
                        "webhook",
                        TriggerType.HTTP,
                        Map.of("authentication", "oidc", "idempotencyRequired", "true")),
                WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID);
        assertTriggerFailure(
                trigger(
                        "nightly",
                        TriggerType.CRON,
                        Map.of(
                                "expression",
                                "bad cron",
                                "timezone",
                                "Mars/Olympus",
                                "misfirePolicy",
                                "catch_up_limited",
                                "catchUpLimit",
                                BigInteger.valueOf(101))),
                WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID);
        assertTriggerFailure(
                trigger(
                        "nightly",
                        TriggerType.CRON,
                        Map.of(
                                "expression",
                                "0 0 * * *",
                                "timezone",
                                "Asia/Shanghai",
                                "misfirePolicy",
                                "fire_once",
                                "catchUpLimit",
                                1)),
                WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID);
        assertTriggerFailure(
                trigger(
                        "nightly",
                        TriggerType.CRON,
                        Map.of(
                                "expression",
                                "0 0 * * *",
                                "timezone",
                                "Asia/Shanghai",
                                "misfirePolicy",
                                "catch_up_limited",
                                "catchUpLimit",
                                new BigDecimal("1.0"))),
                WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID);
    }

    @Test
    void rejectsNodeSchemasAndParameterTypesAcrossEveryVariant() {
        assertNodeFailure(
                node("start", NodeType.START, Map.of("unexpected", true)),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node("end", NodeType.END, Map.of("outcome", "unknown")),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node("end", NodeType.END, Map.of("outcome", false)),
                WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID);
        assertNodeFailure(
                node("invoke", NodeType.HTTP, Map.of()),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node(
                        "invoke",
                        NodeType.HTTP,
                        Map.of(
                                "config",
                                Map.of("connectorRef", CONNECTOR_REFERENCE, "method", "TRACE"))),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node(
                        "invoke",
                        NodeType.HTTP,
                        Map.of(
                                "config",
                                Map.of(
                                        "connectorRef",
                                        CONNECTOR_REFERENCE,
                                        "method",
                                        "GET",
                                        "headers",
                                        Map.of("Invalid Header", "input.value")))),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node("condition", NodeType.CONDITION, Map.of("config", Map.of("expression", 7))),
                WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID);
        assertNodeFailure(
                node(
                        "wait",
                        NodeType.DELAY,
                        Map.of(
                                "config",
                                Map.of("duration", "PT1M", "expiresAtExpression", "input.at"))),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node("wait", NodeType.DELAY, Map.of("config", Map.of("duration", "PT0S"))),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node(
                        "review",
                        NodeType.HUMAN_TASK,
                        Map.of(
                                "config",
                                Map.of(
                                        "candidateRole",
                                        "Finance Admin",
                                        "formSchema",
                                        Map.of(),
                                        "timeoutPolicy",
                                        "fail"))),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
        assertNodeFailure(
                node(
                        "review",
                        NodeType.HUMAN_TASK,
                        Map.of(
                                "config",
                                Map.of(
                                        "candidateRole",
                                        "reviewer",
                                        "assigneeExpression",
                                        "input.owner",
                                        "formSchema",
                                        Map.of("type", "object"),
                                        "timeoutPolicy",
                                        "none"))),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);
    }

    @Test
    void rejectsMalformedUnavailableAndUnauthorizedConnectorReferences() {
        assertNodeFailure(
                httpNode("not-a-reference", List.of()),
                WorkflowConfigurationValidationFailure.CONNECTOR_REFERENCE_INVALID);
        assertNodeFailure(
                httpNode(7, List.of()),
                WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID);

        for (IntegrationResource unavailable :
                List.of(
                        connector(
                                PROJECT_CONNECTOR_ID,
                                TENANT_ID,
                                PROJECT_ID,
                                Status.DISABLED,
                                false),
                        connector(PROJECT_CONNECTOR_ID, TENANT_ID, PROJECT_ID, Status.ACTIVE, true),
                        connector(
                                PROJECT_CONNECTOR_ID,
                                OTHER_TENANT_ID,
                                PROJECT_ID,
                                Status.ACTIVE,
                                false),
                        connector(
                                PROJECT_CONNECTOR_ID,
                                TENANT_ID,
                                OTHER_PROJECT_ID,
                                Status.ACTIVE,
                                false))) {
            WorkflowConfigurationValidationContext context =
                    context(
                            List.of(unavailable, hmacSecret(), httpSecret()),
                            Set.of(new ResourceUseGrant(Kind.CONNECTOR, PROJECT_CONNECTOR_ID)));
            assertNodeFailure(
                    httpNode(CONNECTOR_REFERENCE, List.of()),
                    context,
                    WorkflowConfigurationValidationFailure.CONNECTOR_UNAVAILABLE);
        }

        WorkflowConfigurationValidationContext forbidden =
                context(List.of(projectConnector(), hmacSecret(), httpSecret()), Set.of());
        assertNodeFailure(
                httpNode(CONNECTOR_REFERENCE, List.of()),
                forbidden,
                WorkflowConfigurationValidationFailure.CONNECTOR_USE_FORBIDDEN);
    }

    @Test
    void rejectsMalformedUnavailableAndUnauthorizedSecretReferences() {
        assertNodeFailure(
                httpNode(CONNECTOR_REFERENCE, List.of("bad-reference")),
                WorkflowConfigurationValidationFailure.SECRET_REFERENCE_INVALID);
        assertNodeFailure(
                httpNode(CONNECTOR_REFERENCE, List.of(7)),
                WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID);
        assertNodeFailure(
                httpNode(
                        CONNECTOR_REFERENCE, List.of(HTTP_SECRET_REFERENCE, HTTP_SECRET_REFERENCE)),
                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID);

        for (IntegrationResource unavailable :
                List.of(
                        secret(HTTP_SECRET_ID, TENANT_ID, PROJECT_ID, Status.DISABLED, false),
                        secret(
                                HTTP_SECRET_ID,
                                TENANT_ID,
                                PROJECT_ID,
                                Status.ROTATION_REQUIRED,
                                false),
                        secret(HTTP_SECRET_ID, TENANT_ID, PROJECT_ID, Status.ACTIVE, true),
                        secret(HTTP_SECRET_ID, OTHER_TENANT_ID, PROJECT_ID, Status.ACTIVE, false),
                        secret(
                                HTTP_SECRET_ID,
                                TENANT_ID,
                                OTHER_PROJECT_ID,
                                Status.ACTIVE,
                                false))) {
            WorkflowConfigurationValidationContext context =
                    context(
                            List.of(projectConnector(), unavailable, hmacSecret()),
                            Set.of(
                                    new ResourceUseGrant(Kind.CONNECTOR, PROJECT_CONNECTOR_ID),
                                    new ResourceUseGrant(Kind.SECRET, HTTP_SECRET_ID)));
            assertNodeFailure(
                    httpNode(CONNECTOR_REFERENCE, List.of(HTTP_SECRET_REFERENCE)),
                    context,
                    WorkflowConfigurationValidationFailure.SECRET_UNAVAILABLE);
        }

        WorkflowConfigurationValidationContext forbidden =
                context(
                        List.of(projectConnector(), httpSecret(), hmacSecret()),
                        Set.of(new ResourceUseGrant(Kind.CONNECTOR, PROJECT_CONNECTOR_ID)));
        assertNodeFailure(
                httpNode(CONNECTOR_REFERENCE, List.of(HTTP_SECRET_REFERENCE)),
                forbidden,
                WorkflowConfigurationValidationFailure.SECRET_USE_FORBIDDEN);
    }

    @Test
    void projectResourceShadowsTenantResourceEvenWhenProjectResourceIsDisabled() {
        IntegrationResource disabledProject =
                connector(PROJECT_CONNECTOR_ID, TENANT_ID, PROJECT_ID, Status.DISABLED, false);
        WorkflowConfigurationValidationContext context =
                context(
                        List.of(tenantConnector(), disabledProject, hmacSecret(), httpSecret()),
                        Set.of(
                                new ResourceUseGrant(Kind.CONNECTOR, TENANT_CONNECTOR_ID),
                                new ResourceUseGrant(Kind.CONNECTOR, PROJECT_CONNECTOR_ID)));

        assertNodeFailure(
                httpNode(CONNECTOR_REFERENCE, List.of()),
                context,
                WorkflowConfigurationValidationFailure.CONNECTOR_UNAVAILABLE);
    }

    @Test
    void validatesResourceSnapshotAndGrantBoundaries() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new IntegrationResource(
                                PROJECT_CONNECTOR_ID,
                                Kind.CONNECTOR,
                                "bad-reference",
                                TENANT_ID,
                                PROJECT_ID,
                                Status.ACTIVE,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new IntegrationResource(
                                PROJECT_CONNECTOR_ID,
                                Kind.CONNECTOR,
                                CONNECTOR_REFERENCE,
                                TENANT_ID,
                                PROJECT_ID,
                                Status.ROTATION_REQUIRED,
                                false));
        assertThrows(
                NullPointerException.class, () -> new ResourceUseGrant(null, PROJECT_CONNECTOR_ID));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        context(
                                List.of(projectConnector(), projectConnector()),
                                Set.of(
                                        new ResourceUseGrant(
                                                Kind.CONNECTOR, PROJECT_CONNECTOR_ID))));
        IntegrationResource duplicateScope =
                connector(TENANT_CONNECTOR_ID, TENANT_ID, PROJECT_ID, Status.ACTIVE, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> context(List.of(projectConnector(), duplicateScope), Set.of()));
        assertThrows(
                NullPointerException.class,
                () ->
                        new WorkflowConfigurationValidationContext(
                                null, PROJECT_ID, List.of(), Set.of()));
        assertThrows(NullPointerException.class, () -> validator.validate(null, validContext()));
        assertThrows(
                NullPointerException.class,
                () -> validator.validate(graphValidator.validate(validDefinition()), null));
    }

    @Test
    void exposesStablePayloadSafeFailuresAndImmutableViolations() {
        assertEquals(
                List.of(
                        "trigger_schema_invalid",
                        "node_schema_invalid",
                        "parameter_type_invalid",
                        "connector_reference_invalid",
                        "connector_unavailable",
                        "connector_use_forbidden",
                        "secret_reference_invalid",
                        "secret_unavailable",
                        "secret_use_forbidden"),
                List.of(WorkflowConfigurationValidationFailure.values()).stream()
                        .map(WorkflowConfigurationValidationFailure::wireName)
                        .toList());
        WorkflowConfigurationValidationException exception =
                assertNodeRejected(
                        node("end", NodeType.END, Map.of("outcome", "secret-value")),
                        validContext());
        assertEquals(
                "Workflow configuration rejected: configuration_invalid (1)",
                exception.getMessage());
        assertFalse(exception.getMessage().contains("secret-value"));
        assertEquals("/nodes/5/outcome", exception.violations().getFirst().path());
        assertThrows(UnsupportedOperationException.class, () -> exception.violations().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowConfigurationValidationException(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowConfigurationValidationException(null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowConfigurationViolation(
                                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                                "nodes/0"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorkflowConfigurationViolation(
                                WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                                "/" + "x".repeat(160)));
        assertThrows(
                NullPointerException.class,
                () -> new WorkflowConfigurationViolation(null, "/nodes"));
    }

    private void assertTriggerFailure(
            WorkflowTrigger replacement, WorkflowConfigurationValidationFailure failure) {
        List<WorkflowTrigger> triggers = new ArrayList<>(validTriggers());
        int index =
                replacement.type() == TriggerType.MANUAL
                        ? 0
                        : replacement.type() == TriggerType.HTTP ? 1 : 2;
        triggers.set(index, replacement);
        WorkflowConfigurationValidationException exception =
                assertThrows(
                        WorkflowConfigurationValidationException.class,
                        () -> validate(definition(triggers, validNodes()), validContext()));
        assertTrue(exception.violations().stream().anyMatch(value -> value.failure() == failure));
    }

    private void assertNodeFailure(
            WorkflowNode replacement, WorkflowConfigurationValidationFailure failure) {
        assertNodeFailure(replacement, validContext(), failure);
    }

    private void assertNodeFailure(
            WorkflowNode replacement,
            WorkflowConfigurationValidationContext context,
            WorkflowConfigurationValidationFailure failure) {
        WorkflowConfigurationValidationException exception =
                assertNodeRejected(replacement, context);
        assertTrue(
                exception.violations().stream().anyMatch(value -> value.failure() == failure),
                () -> failure.wireName() + " missing from " + exception.violations());
    }

    private WorkflowConfigurationValidationException assertNodeRejected(
            WorkflowNode replacement, WorkflowConfigurationValidationContext context) {
        List<WorkflowNode> nodes = validNodes();
        int index = nodeIndex(replacement.type());
        nodes.set(index, replacement);
        return assertThrows(
                WorkflowConfigurationValidationException.class,
                () -> validate(definition(validTriggers(), nodes), context));
    }

    private ValidatedWorkflowConfiguration validate(
            WorkflowDefinition definition, WorkflowConfigurationValidationContext context) {
        return validator.validate(graphValidator.validate(definition), context);
    }

    private static WorkflowDefinition validDefinition() {
        return definition(validTriggers(), validNodes());
    }

    private static List<WorkflowTrigger> validTriggers() {
        return List.of(
                trigger("manual", TriggerType.MANUAL, Map.of()),
                trigger(
                        "webhook",
                        TriggerType.HTTP,
                        Map.of(
                                "authentication",
                                "hmac",
                                "idempotencyRequired",
                                true,
                                "hmacSecretRef",
                                HMAC_SECRET_REFERENCE)),
                trigger(
                        "nightly",
                        TriggerType.CRON,
                        Map.of(
                                "expression",
                                "0 0 * * *",
                                "timezone",
                                "Asia/Shanghai",
                                "misfirePolicy",
                                "catch_up_limited",
                                "catchUpLimit",
                                5)));
    }

    private static List<WorkflowNode> validNodes() {
        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(node("start", NodeType.START, Map.of()));
        nodes.add(httpNode(CONNECTOR_REFERENCE, List.of(HTTP_SECRET_REFERENCE)));
        nodes.add(
                node(
                        "condition",
                        NodeType.CONDITION,
                        Map.of("config", Map.of("expression", "input.approved"))));
        nodes.add(node("wait", NodeType.DELAY, Map.of("config", Map.of("duration", "PT5M"))));
        nodes.add(
                node(
                        "review",
                        NodeType.HUMAN_TASK,
                        Map.of(
                                "config",
                                Map.of(
                                        "candidateRole",
                                        "finance_reviewer",
                                        "formSchema",
                                        Map.of("type", "object"),
                                        "deadline",
                                        "PT2H",
                                        "timeoutPolicy",
                                        "fail"))));
        nodes.add(node("end", NodeType.END, Map.of("outcome", "failed")));
        return nodes;
    }

    private static WorkflowNode httpNode(Object connectorReference, List<?> secretReferences) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("connectorRef", connectorReference);
        config.put("method", "POST");
        config.put("pathTemplate", "/v1/orders/{id}");
        config.put("headers", Map.of("X-Trace-Id", "input.trace_id"));
        config.put("bodyExpression", "input");
        config.put("resultExpression", "response.body");
        if (!secretReferences.isEmpty()) {
            config.put("secretRefs", secretReferences);
        }
        return node("invoke", NodeType.HTTP, Map.of("config", config));
    }

    private static WorkflowNode node(String id, NodeType type, Map<String, Object> configuration) {
        return new WorkflowNode(id, type, 1, null, null, null, configuration);
    }

    private static WorkflowTrigger trigger(
            String id, TriggerType type, Map<String, Object> configuration) {
        return new WorkflowTrigger(id, type, configuration);
    }

    private static WorkflowDefinition definition(
            List<WorkflowTrigger> triggers, List<WorkflowNode> nodes) {
        return new WorkflowDefinition(
                1,
                "configuration-validation",
                null,
                1,
                triggers,
                nodes,
                List.of(
                        edge("start_invoke", "start", "invoke", EdgeTransition.SUCCESS, null),
                        edge(
                                "invoke_condition",
                                "invoke",
                                "condition",
                                EdgeTransition.SUCCESS,
                                null),
                        edge(
                                "condition_wait",
                                "condition",
                                "wait",
                                EdgeTransition.CONDITION,
                                "input.approved"),
                        edge(
                                "condition_review",
                                "condition",
                                "review",
                                EdgeTransition.ALWAYS,
                                null),
                        edge("wait_review", "wait", "review", EdgeTransition.SUCCESS, null),
                        edge("review_end", "review", "end", EdgeTransition.SUCCESS, null)),
                new WorkflowParameters(null, null),
                Map.of());
    }

    private static WorkflowEdge edge(
            String id, String from, String to, EdgeTransition transition, String when) {
        return new WorkflowEdge(id, from, to, transition, when, List.of());
    }

    private static int nodeIndex(NodeType type) {
        return switch (type) {
            case START -> 0;
            case HTTP -> 1;
            case CONDITION -> 2;
            case DELAY -> 3;
            case HUMAN_TASK -> 4;
            case END -> 5;
        };
    }

    private static WorkflowConfigurationValidationContext validContext() {
        return context(
                List.of(tenantConnector(), projectConnector(), httpSecret(), hmacSecret()),
                Set.of(
                        new ResourceUseGrant(Kind.CONNECTOR, PROJECT_CONNECTOR_ID),
                        new ResourceUseGrant(Kind.SECRET, HTTP_SECRET_ID),
                        new ResourceUseGrant(Kind.SECRET, HMAC_SECRET_ID)));
    }

    private static WorkflowConfigurationValidationContext context(
            List<IntegrationResource> resources, Set<ResourceUseGrant> extraGrants) {
        Set<ResourceUseGrant> grants = new java.util.HashSet<>(extraGrants);
        grants.add(new ResourceUseGrant(Kind.SECRET, HMAC_SECRET_ID));
        return new WorkflowConfigurationValidationContext(TENANT_ID, PROJECT_ID, resources, grants);
    }

    private static IntegrationResource tenantConnector() {
        return connector(TENANT_CONNECTOR_ID, TENANT_ID, null, Status.ACTIVE, false);
    }

    private static IntegrationResource projectConnector() {
        return connector(PROJECT_CONNECTOR_ID, TENANT_ID, PROJECT_ID, Status.ACTIVE, false);
    }

    private static IntegrationResource connector(
            UUID id, UUID tenantId, UUID projectId, Status status, boolean deleted) {
        return new IntegrationResource(
                id, Kind.CONNECTOR, CONNECTOR_REFERENCE, tenantId, projectId, status, deleted);
    }

    private static IntegrationResource httpSecret() {
        return secret(HTTP_SECRET_ID, TENANT_ID, PROJECT_ID, Status.ACTIVE, false);
    }

    private static IntegrationResource hmacSecret() {
        return new IntegrationResource(
                HMAC_SECRET_ID,
                Kind.SECRET,
                HMAC_SECRET_REFERENCE,
                TENANT_ID,
                null,
                Status.ACTIVE,
                false);
    }

    private static IntegrationResource secret(
            UUID id, UUID tenantId, UUID projectId, Status status, boolean deleted) {
        return new IntegrationResource(
                id, Kind.SECRET, HTTP_SECRET_REFERENCE, tenantId, projectId, status, deleted);
    }
}
