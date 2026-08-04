package com.workflowplatform.controlplane.definition;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, typed node/trigger configuration produced after graph and resource validation. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class ValidatedWorkflowConfiguration {

    private final ValidatedWorkflowGraph graph;
    private final Map<String, TriggerConfiguration> triggers;
    private final Map<String, NodeConfiguration> nodes;

    ValidatedWorkflowConfiguration(
            ValidatedWorkflowGraph graph,
            Map<String, TriggerConfiguration> triggers,
            Map<String, NodeConfiguration> nodes) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.triggers = immutableMap(triggers);
        this.nodes = immutableMap(nodes);
    }

    public ValidatedWorkflowGraph graph() {
        return graph;
    }

    public Map<String, TriggerConfiguration> triggers() {
        return immutableMap(triggers);
    }

    public Map<String, NodeConfiguration> nodes() {
        return immutableMap(nodes);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values) {
        Objects.requireNonNull(values, "values");
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Validated trigger configuration variants. */
    public sealed interface TriggerConfiguration
            permits ManualTriggerConfiguration,
                    HttpTriggerConfiguration,
                    CronTriggerConfiguration {}

    /** Validated node configuration variants. */
    public sealed interface NodeConfiguration
            permits StartNodeConfiguration,
                    EndNodeConfiguration,
                    HttpNodeConfiguration,
                    ConditionNodeConfiguration,
                    DelayNodeConfiguration,
                    HumanTaskNodeConfiguration {}

    public record ManualTriggerConfiguration() implements TriggerConfiguration {}

    public record HttpTriggerConfiguration(
            HttpAuthentication authentication, Optional<ResolvedResource> hmacSecret)
            implements TriggerConfiguration {

        public HttpTriggerConfiguration {
            Objects.requireNonNull(authentication, "authentication");
            Objects.requireNonNull(hmacSecret, "hmacSecret");
        }
    }

    public record CronTriggerConfiguration(
            String expression, ZoneId timezone, MisfirePolicy misfirePolicy, Integer catchUpLimit)
            implements TriggerConfiguration {

        public CronTriggerConfiguration {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(misfirePolicy, "misfirePolicy");
        }
    }

    public record StartNodeConfiguration() implements NodeConfiguration {}

    public record EndNodeConfiguration(EndOutcome outcome) implements NodeConfiguration {

        public EndNodeConfiguration {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record HttpNodeConfiguration(
            ResolvedResource connector,
            HttpMethod method,
            Optional<String> pathTemplate,
            Map<String, String> headers,
            Optional<String> bodyExpression,
            Optional<String> resultExpression,
            List<ResolvedResource> secrets)
            implements NodeConfiguration {

        public HttpNodeConfiguration {
            Objects.requireNonNull(connector, "connector");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(pathTemplate, "pathTemplate");
            headers = immutableMap(headers);
            Objects.requireNonNull(bodyExpression, "bodyExpression");
            Objects.requireNonNull(resultExpression, "resultExpression");
            secrets = List.copyOf(secrets);
        }

        @Override
        public Map<String, String> headers() {
            return immutableMap(headers);
        }
    }

    public record ConditionNodeConfiguration(String expression) implements NodeConfiguration {

        public ConditionNodeConfiguration {
            Objects.requireNonNull(expression, "expression");
        }
    }

    public record DelayNodeConfiguration(
            Optional<Duration> duration, Optional<String> expiresAtExpression)
            implements NodeConfiguration {

        public DelayNodeConfiguration {
            Objects.requireNonNull(duration, "duration");
            Objects.requireNonNull(expiresAtExpression, "expiresAtExpression");
        }
    }

    public record HumanTaskNodeConfiguration(
            Optional<String> candidateRole,
            Optional<String> assigneeExpression,
            Map<String, Object> formSchema,
            Optional<Duration> deadline,
            HumanTaskTimeoutPolicy timeoutPolicy)
            implements NodeConfiguration {

        public HumanTaskNodeConfiguration {
            Objects.requireNonNull(candidateRole, "candidateRole");
            Objects.requireNonNull(assigneeExpression, "assigneeExpression");
            formSchema = immutableMap(formSchema);
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
        }

        @Override
        public Map<String, Object> formSchema() {
            return immutableMap(formSchema);
        }
    }

    public record ResolvedResource(
            UUID id, IntegrationResource.Kind kind, String reference, ResourceScope scope) {

        public ResolvedResource {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public enum HttpAuthentication {
        OIDC,
        HMAC
    }

    public enum MisfirePolicy {
        SKIP,
        FIRE_ONCE,
        CATCH_UP_LIMITED
    }

    public enum EndOutcome {
        SUCCEEDED,
        FAILED
    }

    public enum HttpMethod {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE
    }

    public enum HumanTaskTimeoutPolicy {
        NONE,
        FAIL,
        AUTO_APPROVE,
        AUTO_REJECT,
        ESCALATE
    }

    public enum ResourceScope {
        TENANT,
        PROJECT
    }
}
