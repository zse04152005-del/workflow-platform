package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.WorkflowNode;
import com.workflowplatform.contracts.dsl.WorkflowTrigger;
import com.workflowplatform.controlplane.definition.IntegrationResource.Kind;
import com.workflowplatform.controlplane.definition.IntegrationResource.Status;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.ConditionNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.CronTriggerConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.DelayNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.EndNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.EndOutcome;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpAuthentication;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpMethod;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpTriggerConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HumanTaskNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HumanTaskTimeoutPolicy;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.ManualTriggerConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.MisfirePolicy;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.NodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.ResolvedResource;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.ResourceScope;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.StartNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.TriggerConfiguration;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Deterministic G3-04 schema, parameter, reference and use-authorization validator. */
@Component
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ReturnEmptyCollectionRatherThanNull"})
final class DeterministicWorkflowConfigurationValidator implements WorkflowConfigurationValidator {

    private static final int MAXIMUM_EXPRESSION_LENGTH = 2048;
    private static final int MAXIMUM_SECRET_REFERENCES = 20;
    private static final int MAXIMUM_HEADERS = 50;
    private static final Pattern REFERENCE =
            Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");
    private static final Pattern CRON =
            Pattern.compile("^[0-9*/?,LW#\\-]+(?:[ \\t]+[0-9*/?,LW#\\-]+){4,6}$");
    private static final Pattern TIMEZONE =
            Pattern.compile("^[A-Za-z_+-]+(?:/[A-Za-z0-9_+\\-]+)+$");
    private static final Pattern ROLE = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern HEADER = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$");

    private static final Set<String> HTTP_TRIGGER_FIELDS =
            Set.of("authentication", "idempotencyRequired", "hmacSecretRef");
    private static final Set<String> CRON_TRIGGER_FIELDS =
            Set.of("expression", "timezone", "misfirePolicy", "catchUpLimit");
    private static final Set<String> HTTP_NODE_FIELDS =
            Set.of(
                    "connectorRef",
                    "secretRefs",
                    "method",
                    "pathTemplate",
                    "headers",
                    "bodyExpression",
                    "resultExpression");
    private static final Set<String> HUMAN_TASK_FIELDS =
            Set.of(
                    "candidateRole",
                    "assigneeExpression",
                    "formSchema",
                    "deadline",
                    "timeoutPolicy");

    @Override
    public ValidatedWorkflowConfiguration validate(
            ValidatedWorkflowGraph graph, WorkflowConfigurationValidationContext context) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(context, "context");
        List<WorkflowConfigurationViolation> violations = new ArrayList<>();
        Map<String, TriggerConfiguration> triggers = new LinkedHashMap<>();
        Map<String, NodeConfiguration> nodes = new LinkedHashMap<>();

        List<WorkflowTrigger> definitionTriggers = graph.definition().triggers();
        for (int index = 0; index < definitionTriggers.size(); index++) {
            WorkflowTrigger trigger = definitionTriggers.get(index);
            TriggerConfiguration configuration =
                    validateTrigger(trigger, index, context, violations);
            if (configuration != null) {
                triggers.put(trigger.id(), configuration);
            }
        }
        List<WorkflowNode> definitionNodes = graph.definition().nodes();
        for (int index = 0; index < definitionNodes.size(); index++) {
            WorkflowNode node = definitionNodes.get(index);
            NodeConfiguration configuration = validateNode(node, index, context, violations);
            if (configuration != null) {
                nodes.put(node.id(), configuration);
            }
        }
        if (!violations.isEmpty()) {
            throw new WorkflowConfigurationValidationException(violations);
        }
        return new ValidatedWorkflowConfiguration(graph, triggers, nodes);
    }

    private TriggerConfiguration validateTrigger(
            WorkflowTrigger trigger,
            int index,
            WorkflowConfigurationValidationContext context,
            List<WorkflowConfigurationViolation> violations) {
        String base = "/triggers/" + index;
        return switch (trigger.type()) {
            case MANUAL -> validateManualTrigger(trigger.configuration(), base, violations);
            case HTTP -> validateHttpTrigger(trigger.configuration(), base, context, violations);
            case CRON -> validateCronTrigger(trigger.configuration(), base, violations);
        };
    }

    private TriggerConfiguration validateManualTrigger(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        if (!values.isEmpty()) {
            add(violations, WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID, base);
            return null;
        }
        return new ManualTriggerConfiguration();
    }

    private TriggerConfiguration validateHttpTrigger(
            Map<String, Object> values,
            String base,
            WorkflowConfigurationValidationContext context,
            List<WorkflowConfigurationViolation> violations) {
        if (!hasSchema(
                values, Set.of("authentication", "idempotencyRequired"), HTTP_TRIGGER_FIELDS)) {
            add(violations, WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID, base);
            return null;
        }
        int initialFailures = violations.size();
        String authentication =
                text(values.get("authentication"), base + "/authentication", violations);
        boolean idempotency =
                bool(values.get("idempotencyRequired"), base + "/idempotencyRequired", violations);
        if (!idempotency) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID,
                    base + "/idempotencyRequired");
        }
        HttpAuthentication typedAuthentication = httpAuthentication(authentication);
        if (authentication != null && typedAuthentication == null) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID,
                    base + "/authentication");
        }
        boolean hmacReferencePresent = values.containsKey("hmacSecretRef");
        if (typedAuthentication == HttpAuthentication.HMAC && !hmacReferencePresent
                || typedAuthentication == HttpAuthentication.OIDC && hmacReferencePresent) {
            add(violations, WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID, base);
        }
        ResolvedResource hmacResource = null;
        if (hmacReferencePresent) {
            hmacResource =
                    resolveReference(
                            values.get("hmacSecretRef"),
                            Kind.SECRET,
                            base + "/hmacSecretRef",
                            context,
                            violations);
        }
        if (violations.size() != initialFailures) {
            return null;
        }
        return new HttpTriggerConfiguration(typedAuthentication, Optional.ofNullable(hmacResource));
    }

    private TriggerConfiguration validateCronTrigger(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        if (!hasSchema(
                values, Set.of("expression", "timezone", "misfirePolicy"), CRON_TRIGGER_FIELDS)) {
            add(violations, WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID, base);
            return null;
        }
        int initialFailures = violations.size();
        String expression = text(values.get("expression"), base + "/expression", violations);
        String timezone = text(values.get("timezone"), base + "/timezone", violations);
        String policy = text(values.get("misfirePolicy"), base + "/misfirePolicy", violations);
        if (expression != null
                && (expression.length() < 9
                        || expression.length() > 120
                        || !CRON.matcher(expression).matches())) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID,
                    base + "/expression");
        }
        ZoneId zone = zoneId(timezone);
        if (timezone != null && zone == null) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID,
                    base + "/timezone");
        }
        MisfirePolicy typedPolicy = misfirePolicy(policy);
        if (policy != null && typedPolicy == null) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID,
                    base + "/misfirePolicy");
        }
        Integer catchUpLimit = null;
        boolean hasCatchUpLimit = values.containsKey("catchUpLimit");
        if (hasCatchUpLimit) {
            catchUpLimit = integer(values.get("catchUpLimit"), base + "/catchUpLimit", violations);
            if (catchUpLimit != null && (catchUpLimit < 1 || catchUpLimit > 100)) {
                add(
                        violations,
                        WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID,
                        base + "/catchUpLimit");
            }
        }
        if (typedPolicy == MisfirePolicy.CATCH_UP_LIMITED && !hasCatchUpLimit
                || typedPolicy != null
                        && typedPolicy != MisfirePolicy.CATCH_UP_LIMITED
                        && hasCatchUpLimit) {
            add(violations, WorkflowConfigurationValidationFailure.TRIGGER_SCHEMA_INVALID, base);
        }
        if (violations.size() != initialFailures) {
            return null;
        }
        return new CronTriggerConfiguration(expression, zone, typedPolicy, catchUpLimit);
    }

    private NodeConfiguration validateNode(
            WorkflowNode node,
            int index,
            WorkflowConfigurationValidationContext context,
            List<WorkflowConfigurationViolation> violations) {
        String base = "/nodes/" + index;
        return switch (node.type()) {
            case START -> validateStartNode(node.configuration(), base, violations);
            case END -> validateEndNode(node.configuration(), base, violations);
            case HTTP -> validateHttpNode(node.configuration(), base, context, violations);
            case CONDITION -> validateConditionNode(node.configuration(), base, violations);
            case DELAY -> validateDelayNode(node.configuration(), base, violations);
            case HUMAN_TASK -> validateHumanTaskNode(node.configuration(), base, violations);
        };
    }

    private NodeConfiguration validateStartNode(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        if (!values.isEmpty()) {
            add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, base);
            return null;
        }
        return new StartNodeConfiguration();
    }

    private NodeConfiguration validateEndNode(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        if (!hasSchema(values, Set.of(), Set.of("outcome"))) {
            add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, base);
            return null;
        }
        if (!values.containsKey("outcome")) {
            return new EndNodeConfiguration(EndOutcome.SUCCEEDED);
        }
        int initialFailures = violations.size();
        String outcome = text(values.get("outcome"), base + "/outcome", violations);
        EndOutcome typedOutcome = endOutcome(outcome);
        if (outcome != null && typedOutcome == null) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/outcome");
        }
        return violations.size() == initialFailures ? new EndNodeConfiguration(typedOutcome) : null;
    }

    private NodeConfiguration validateHttpNode(
            Map<String, Object> values,
            String base,
            WorkflowConfigurationValidationContext context,
            List<WorkflowConfigurationViolation> violations) {
        if (!hasSchema(values, Set.of("config"), Set.of("config"))) {
            add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, base);
            return null;
        }
        Map<String, Object> config = object(values.get("config"), base + "/config", violations);
        if (config == null) {
            return null;
        }
        if (!hasSchema(config, Set.of("connectorRef", "method"), HTTP_NODE_FIELDS)) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config");
            return null;
        }
        int initialFailures = violations.size();
        ResolvedResource connector =
                resolveReference(
                        config.get("connectorRef"),
                        Kind.CONNECTOR,
                        base + "/config/connectorRef",
                        context,
                        violations);
        String method = text(config.get("method"), base + "/config/method", violations);
        HttpMethod typedMethod = httpMethod(method);
        if (method != null && typedMethod == null) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config/method");
        }
        Optional<String> pathTemplate =
                optionalText(config, "pathTemplate", base, 1000, violations);
        Map<String, String> headers = headers(config.get("headers"), base, violations);
        Optional<String> bodyExpression =
                optionalExpression(config, "bodyExpression", base, violations);
        Optional<String> resultExpression =
                optionalExpression(config, "resultExpression", base, violations);
        List<ResolvedResource> secrets =
                secretReferences(config.get("secretRefs"), base, context, violations);
        if (violations.size() != initialFailures) {
            return null;
        }
        return new HttpNodeConfiguration(
                connector,
                typedMethod,
                pathTemplate,
                headers,
                bodyExpression,
                resultExpression,
                secrets);
    }

    private NodeConfiguration validateConditionNode(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        Map<String, Object> config = singleConfig(values, base, violations);
        if (config == null) {
            return null;
        }
        if (!hasSchema(config, Set.of("expression"), Set.of("expression"))) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config");
            return null;
        }
        String expression =
                expression(config.get("expression"), base + "/config/expression", violations);
        return expression == null ? null : new ConditionNodeConfiguration(expression);
    }

    private NodeConfiguration validateDelayNode(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        Map<String, Object> config = singleConfig(values, base, violations);
        if (config == null) {
            return null;
        }
        boolean durationPresent = config.containsKey("duration");
        boolean expressionPresent = config.containsKey("expiresAtExpression");
        if (!hasSchema(config, Set.of(), Set.of("duration", "expiresAtExpression"))
                || durationPresent == expressionPresent) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config");
            return null;
        }
        if (durationPresent) {
            Duration duration =
                    duration(config.get("duration"), base + "/config/duration", violations);
            return duration == null
                    ? null
                    : new DelayNodeConfiguration(Optional.of(duration), Optional.empty());
        }
        String expression =
                expression(
                        config.get("expiresAtExpression"),
                        base + "/config/expiresAtExpression",
                        violations);
        return expression == null
                ? null
                : new DelayNodeConfiguration(Optional.empty(), Optional.of(expression));
    }

    private NodeConfiguration validateHumanTaskNode(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        Map<String, Object> config = singleConfig(values, base, violations);
        if (config == null) {
            return null;
        }
        boolean rolePresent = config.containsKey("candidateRole");
        boolean assigneePresent = config.containsKey("assigneeExpression");
        if (!hasSchema(config, Set.of("formSchema", "timeoutPolicy"), HUMAN_TASK_FIELDS)
                || rolePresent == assigneePresent) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config");
            return null;
        }
        int initialFailures = violations.size();
        Optional<String> role = Optional.empty();
        if (rolePresent) {
            String candidateRole =
                    text(config.get("candidateRole"), base + "/config/candidateRole", violations);
            if (candidateRole != null
                    && (candidateRole.length() > 80 || !ROLE.matcher(candidateRole).matches())) {
                add(
                        violations,
                        WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                        base + "/config/candidateRole");
            } else {
                role = Optional.ofNullable(candidateRole);
            }
        }
        Optional<String> assignee = Optional.empty();
        if (assigneePresent) {
            assignee =
                    Optional.ofNullable(
                            expression(
                                    config.get("assigneeExpression"),
                                    base + "/config/assigneeExpression",
                                    violations));
        }
        Map<String, Object> formSchema =
                object(config.get("formSchema"), base + "/config/formSchema", violations);
        if (formSchema != null && formSchema.isEmpty()) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config/formSchema");
        }
        String policy =
                text(config.get("timeoutPolicy"), base + "/config/timeoutPolicy", violations);
        HumanTaskTimeoutPolicy typedPolicy = timeoutPolicy(policy);
        if (policy != null && typedPolicy == null) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config/timeoutPolicy");
        }
        boolean deadlinePresent = config.containsKey("deadline");
        Duration deadline =
                deadlinePresent
                        ? duration(config.get("deadline"), base + "/config/deadline", violations)
                        : null;
        if (typedPolicy == HumanTaskTimeoutPolicy.NONE && deadlinePresent
                || typedPolicy != null
                        && typedPolicy != HumanTaskTimeoutPolicy.NONE
                        && !deadlinePresent) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config");
        }
        if (violations.size() != initialFailures) {
            return null;
        }
        return new HumanTaskNodeConfiguration(
                role, assignee, formSchema, Optional.ofNullable(deadline), typedPolicy);
    }

    private Map<String, Object> singleConfig(
            Map<String, Object> values,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        if (!hasSchema(values, Set.of("config"), Set.of("config"))) {
            add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, base);
            return null;
        }
        return object(values.get("config"), base + "/config", violations);
    }

    private List<ResolvedResource> secretReferences(
            Object value,
            String base,
            WorkflowConfigurationValidationContext context,
            List<WorkflowConfigurationViolation> violations) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> references)) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID,
                    base + "/config/secretRefs");
            return List.of();
        }
        if (references.size() > MAXIMUM_SECRET_REFERENCES) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config/secretRefs");
            return List.of();
        }
        List<ResolvedResource> resolved = new ArrayList<>(references.size());
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < references.size(); index++) {
            Object reference = references.get(index);
            String path = base + "/config/secretRefs/" + index;
            if (reference instanceof String string && !unique.add(string)) {
                add(
                        violations,
                        WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                        base + "/config/secretRefs");
                continue;
            }
            ResolvedResource resource =
                    resolveReference(reference, Kind.SECRET, path, context, violations);
            if (resource != null) {
                resolved.add(resource);
            }
        }
        return resolved;
    }

    private ResolvedResource resolveReference(
            Object value,
            Kind kind,
            String path,
            WorkflowConfigurationValidationContext context,
            List<WorkflowConfigurationViolation> violations) {
        String reference = text(value, path, violations);
        if (reference == null) {
            return null;
        }
        WorkflowConfigurationValidationFailure invalid =
                kind == Kind.CONNECTOR
                        ? WorkflowConfigurationValidationFailure.CONNECTOR_REFERENCE_INVALID
                        : WorkflowConfigurationValidationFailure.SECRET_REFERENCE_INVALID;
        if (reference.length() > 200 || !REFERENCE.matcher(reference).matches()) {
            add(violations, invalid, path);
            return null;
        }
        IntegrationResource resource = resolveResource(kind, reference, context);
        if (resource == null || resource.deleted() || resource.status() != Status.ACTIVE) {
            add(violations, unavailableFailure(kind), path);
            return null;
        }
        if (!context.useGrants().contains(new ResourceUseGrant(kind, resource.id()))) {
            add(violations, forbiddenFailure(kind), path);
            return null;
        }
        ResourceScope scope =
                resource.projectId() == null ? ResourceScope.TENANT : ResourceScope.PROJECT;
        return new ResolvedResource(resource.id(), kind, reference, scope);
    }

    private IntegrationResource resolveResource(
            Kind kind, String reference, WorkflowConfigurationValidationContext context) {
        IntegrationResource tenantResource = null;
        for (IntegrationResource resource : context.resources()) {
            if (resource.kind() != kind
                    || !resource.reference().equals(reference)
                    || !resource.tenantId().equals(context.tenantId())) {
                continue;
            }
            if (context.projectId().equals(resource.projectId())) {
                return resource;
            }
            if (resource.projectId() == null) {
                tenantResource = resource;
            }
        }
        return tenantResource;
    }

    private Map<String, String> headers(
            Object value, String base, List<WorkflowConfigurationViolation> violations) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> values = object(value, base + "/config/headers", violations);
        if (values == null) {
            return Map.of();
        }
        if (values.size() > MAXIMUM_HEADERS) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config/headers");
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!HEADER.matcher(entry.getKey()).matches()) {
                add(
                        violations,
                        WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                        base + "/config/headers");
                continue;
            }
            String expression = expression(entry.getValue(), base + "/config/headers", violations);
            if (expression != null) {
                result.put(entry.getKey(), expression);
            }
        }
        return result;
    }

    private Optional<String> optionalText(
            Map<String, Object> values,
            String field,
            String base,
            int maximumLength,
            List<WorkflowConfigurationViolation> violations) {
        if (!values.containsKey(field)) {
            return Optional.empty();
        }
        String value = text(values.get(field), base + "/config/" + field, violations);
        if (value != null && value.length() > maximumLength) {
            add(
                    violations,
                    WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID,
                    base + "/config/" + field);
            return Optional.empty();
        }
        return Optional.ofNullable(value);
    }

    private Optional<String> optionalExpression(
            Map<String, Object> values,
            String field,
            String base,
            List<WorkflowConfigurationViolation> violations) {
        return values.containsKey(field)
                ? Optional.ofNullable(
                        expression(values.get(field), base + "/config/" + field, violations))
                : Optional.empty();
    }

    private String expression(
            Object value, String path, List<WorkflowConfigurationViolation> violations) {
        String expression = text(value, path, violations);
        if (expression != null
                && (expression.isBlank() || expression.length() > MAXIMUM_EXPRESSION_LENGTH)) {
            add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, path);
            return null;
        }
        return expression;
    }

    private Duration duration(
            Object value, String path, List<WorkflowConfigurationViolation> violations) {
        String text = text(value, path, violations);
        if (text == null) {
            return null;
        }
        try {
            Duration duration = Duration.parse(text);
            if (duration.isZero() || duration.isNegative()) {
                add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, path);
                return null;
            }
            return duration;
        } catch (DateTimeParseException exception) {
            add(violations, WorkflowConfigurationValidationFailure.NODE_SCHEMA_INVALID, path);
            return null;
        }
    }

    private Map<String, Object> object(
            Object value, String path, List<WorkflowConfigurationViolation> violations) {
        if (!(value instanceof Map<?, ?> map)) {
            add(violations, WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID, path);
            return null;
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                add(
                        violations,
                        WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID,
                        path);
                return null;
            }
            typed.put(key, entry.getValue());
        }
        return typed;
    }

    private String text(
            Object value, String path, List<WorkflowConfigurationViolation> violations) {
        if (value instanceof String text) {
            return text;
        }
        add(violations, WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID, path);
        return null;
    }

    private boolean bool(
            Object value, String path, List<WorkflowConfigurationViolation> violations) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        add(violations, WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID, path);
        return false;
    }

    private Integer integer(
            Object value, String path, List<WorkflowConfigurationViolation> violations) {
        long number;
        if (value instanceof BigInteger integer && integer.bitLength() < Integer.SIZE) {
            number = integer.longValue();
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            number = ((Number) value).longValue();
        } else {
            add(violations, WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID, path);
            return null;
        }
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            add(violations, WorkflowConfigurationValidationFailure.PARAMETER_TYPE_INVALID, path);
            return null;
        }
        return (int) number;
    }

    private static boolean hasSchema(
            Map<String, Object> values, Set<String> required, Set<String> allowed) {
        return values.keySet().containsAll(required) && allowed.containsAll(values.keySet());
    }

    private static ZoneId zoneId(String value) {
        if (value == null
                || value.length() < 3
                || value.length() > 80
                || !TIMEZONE.matcher(value).matches()) {
            return null;
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static HttpAuthentication httpAuthentication(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "oidc" -> HttpAuthentication.OIDC;
            case "hmac" -> HttpAuthentication.HMAC;
            default -> null;
        };
    }

    private static MisfirePolicy misfirePolicy(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "skip" -> MisfirePolicy.SKIP;
            case "fire_once" -> MisfirePolicy.FIRE_ONCE;
            case "catch_up_limited" -> MisfirePolicy.CATCH_UP_LIMITED;
            default -> null;
        };
    }

    private static EndOutcome endOutcome(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "succeeded" -> EndOutcome.SUCCEEDED;
            case "failed" -> EndOutcome.FAILED;
            default -> null;
        };
    }

    private static HttpMethod httpMethod(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HttpMethod.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static HumanTaskTimeoutPolicy timeoutPolicy(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "none" -> HumanTaskTimeoutPolicy.NONE;
            case "fail" -> HumanTaskTimeoutPolicy.FAIL;
            case "auto_approve" -> HumanTaskTimeoutPolicy.AUTO_APPROVE;
            case "auto_reject" -> HumanTaskTimeoutPolicy.AUTO_REJECT;
            case "escalate" -> HumanTaskTimeoutPolicy.ESCALATE;
            default -> null;
        };
    }

    private static WorkflowConfigurationValidationFailure unavailableFailure(Kind kind) {
        return kind == Kind.CONNECTOR
                ? WorkflowConfigurationValidationFailure.CONNECTOR_UNAVAILABLE
                : WorkflowConfigurationValidationFailure.SECRET_UNAVAILABLE;
    }

    private static WorkflowConfigurationValidationFailure forbiddenFailure(Kind kind) {
        return kind == Kind.CONNECTOR
                ? WorkflowConfigurationValidationFailure.CONNECTOR_USE_FORBIDDEN
                : WorkflowConfigurationValidationFailure.SECRET_USE_FORBIDDEN;
    }

    private static void add(
            List<WorkflowConfigurationViolation> violations,
            WorkflowConfigurationValidationFailure failure,
            String path) {
        violations.add(new WorkflowConfigurationViolation(failure, path));
    }
}
