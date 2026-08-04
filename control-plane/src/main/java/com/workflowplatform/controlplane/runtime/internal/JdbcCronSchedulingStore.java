package com.workflowplatform.controlplane.runtime.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflowplatform.controlplane.definition.TimeOrderedUuidV7;
import com.workflowplatform.controlplane.runtime.CronSchedulingStore;
import com.workflowplatform.controlplane.runtime.CronTriggerCalculator;
import com.workflowplatform.controlplane.runtime.RuntimeEntityType;
import com.workflowplatform.controlplane.runtime.RuntimeStateTransitionService;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionActor;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionCommand;
import com.workflowplatform.controlplane.runtime.RuntimeTransitionEffects;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerResult;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerService;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cron occurrence store using unique slots, RLS and SKIP LOCKED transactionally. */
@Component
final class JdbcCronSchedulingStore implements CronSchedulingStore {

    private static final String PENDING = "PENDING";
    private static final String SKIPPED = "SKIPPED";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RuntimeStateTransitionService transitions;
    private final RuntimeTriggerService triggers;
    private final ObjectMapper objectMapper;
    private final CronTriggerCalculator calculator = new CronTriggerCalculator();

    JdbcCronSchedulingStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            RuntimeStateTransitionService transitionService,
            RuntimeTriggerService triggerService,
            ObjectMapper jsonMapper) {
        jdbc = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        transitions = Objects.requireNonNull(transitionService, "transitionService");
        triggers = Objects.requireNonNull(triggerService, "triggerService");
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    @Override
    public List<CronOccurrence> materialize(
            WorkflowRequestContext context,
            UUID triggerConfigId,
            Instant now,
            int catchUpLimit,
            boolean allowOverlap) {
        List<CronOccurrence> result =
                transactions.execute(
                        status ->
                                materializeInTransaction(
                                        context, triggerConfigId, now, catchUpLimit, allowOverlap));
        if (result == null) {
            throw new IllegalStateException("Cron materialization transaction returned no result");
        }
        return List.copyOf(result);
    }

    @Override
    public List<FiredCronOccurrence> fireDue(
            WorkflowRequestContext context,
            String schedulerId,
            Instant now,
            int limit,
            String traceId) {
        List<FiredCronOccurrence> result =
                transactions.execute(
                        status -> fireDueInTransaction(context, schedulerId, now, limit, traceId));
        if (result == null) {
            throw new IllegalStateException("Cron firing transaction returned no result");
        }
        return List.copyOf(result);
    }

    private List<CronOccurrence> materializeInTransaction(
            WorkflowRequestContext context,
            UUID triggerConfigId,
            Instant now,
            int catchUpLimit,
            boolean allowOverlap) {
        JdbcRuntimeSupport.setTenantContext(jdbc, context);
        CronTrigger trigger = lockTrigger(context, triggerConfigId);
        Instant after = lastOccurrence(context, trigger).orElse(trigger.createdAt());
        List<CronTriggerCalculator.OccurrenceDecision> decisions =
                calculator.dueOccurrences(
                        trigger.expression(),
                        ZoneId.of(trigger.timezone()),
                        after,
                        now,
                        trigger.misfirePolicy(),
                        catchUpLimit);
        long activeInstances = activeInstanceCount(context, trigger.workflowVersionId());
        List<CronOccurrence> created = new ArrayList<>();
        for (CronTriggerCalculator.OccurrenceDecision decision : decisions) {
            boolean overlapAccepted = calculator.overlapAllowed(allowOverlap, activeInstances);
            String occurrenceStatus =
                    decision.decision() == CronTriggerCalculator.Decision.SKIP || !overlapAccepted
                            ? SKIPPED
                            : PENDING;
            UUID id = TimeOrderedUuidV7.generate();
            List<UUID> inserted =
                    jdbc.query(
                            "INSERT INTO public.schedule_occurrence (id, tenant_id, created_by, "
                                    + "updated_by, trigger_config_id, scheduled_at, timezone, "
                                    + "misfire_policy, status, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT (tenant_id, trigger_config_id, scheduled_at) DO NOTHING "
                                    + "RETURNING id",
                            (result, rowNumber) -> result.getObject("id", UUID.class),
                            id,
                            context.tenantId(),
                            context.actorId(),
                            context.actorId(),
                            trigger.id(),
                            atUtc(decision.scheduledAt()),
                            trigger.timezone(),
                            trigger.misfirePolicy().name().toLowerCase(Locale.ROOT),
                            occurrenceStatus,
                            SKIPPED.equals(occurrenceStatus) ? atUtc(now) : null);
            if (!inserted.isEmpty()) {
                created.add(
                        new CronOccurrence(
                                inserted.getFirst(),
                                trigger.id(),
                                decision.scheduledAt(),
                                occurrenceStatus));
            }
        }
        return created;
    }

    private List<FiredCronOccurrence> fireDueInTransaction(
            WorkflowRequestContext context,
            String schedulerId,
            Instant now,
            int limit,
            String traceId) {
        JdbcRuntimeSupport.setTenantContext(jdbc, context);
        List<DueOccurrence> due =
                jdbc.query(
                        "SELECT o.id, o.version, o.scheduled_at, v.definition_id "
                                + "FROM public.schedule_occurrence o JOIN public.trigger_config t "
                                + "ON t.tenant_id = o.tenant_id AND t.id = o.trigger_config_id "
                                + "JOIN public.workflow_version v ON v.tenant_id = t.tenant_id "
                                + "AND v.id = t.workflow_version_id WHERE o.tenant_id = ? "
                                + "AND o.status = 'PENDING' AND o.scheduled_at <= ? "
                                + "ORDER BY o.scheduled_at, o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED",
                        (result, rowNumber) ->
                                new DueOccurrence(
                                        result.getObject("id", UUID.class),
                                        result.getLong("version"),
                                        result.getObject("scheduled_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getObject("definition_id", UUID.class)),
                        context.tenantId(),
                        atUtc(now),
                        limit);
        List<FiredCronOccurrence> fired = new ArrayList<>();
        for (DueOccurrence occurrence : due) {
            transitions.transition(
                    command(
                            context,
                            occurrence.id(),
                            "claim_occurrence",
                            PENDING,
                            occurrence.version(),
                            "CLAIMED",
                            schedulerId,
                            traceId,
                            RuntimeTransitionEffects.none()));
            ObjectNode input = objectMapper.createObjectNode();
            input.put("trigger", "cron");
            input.put("occurrenceId", occurrence.id().toString());
            input.put("scheduledAt", occurrence.scheduledAt().toString());
            String uniqueSuffix = occurrence.id().toString().replace("-", "");
            RuntimeTriggerResult instance =
                    triggers.manual(
                            context,
                            occurrence.definitionId(),
                            input,
                            "cron/" + occurrence.id(),
                            "cron-" + uniqueSuffix,
                            traceId);
            transitions.transition(
                    command(
                            context,
                            occurrence.id(),
                            "fire_occurrence",
                            "CLAIMED",
                            occurrence.version() + 1,
                            "FIRED",
                            schedulerId,
                            traceId,
                            fired(instance.instanceId())));
            fired.add(
                    new FiredCronOccurrence(
                            occurrence.id(), instance.instanceId(), occurrence.scheduledAt()));
        }
        return fired;
    }

    private CronTrigger lockTrigger(WorkflowRequestContext context, UUID triggerConfigId) {
        return jdbc
                .query(
                        "SELECT t.id, t.workflow_version_id, t.created_at, t.timezone, "
                                + "t.misfire_policy, t.config ->> 'expression' AS expression "
                                + "FROM public.trigger_config t WHERE t.tenant_id = ? AND t.id = ? "
                                + "AND t.trigger_type = 'cron'",
                        (result, rowNumber) ->
                                new CronTrigger(
                                        result.getObject("id", UUID.class),
                                        result.getObject("workflow_version_id", UUID.class),
                                        result.getObject("created_at", OffsetDateTime.class)
                                                .toInstant(),
                                        result.getString("timezone"),
                                        CronTriggerCalculator.MisfirePolicy.valueOf(
                                                result.getString("misfire_policy")
                                                        .toUpperCase(Locale.ROOT)),
                                        result.getString("expression")),
                        context.tenantId(),
                        triggerConfigId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Cron trigger was not found"));
    }

    private java.util.Optional<Instant> lastOccurrence(
            WorkflowRequestContext context, CronTrigger trigger) {
        OffsetDateTime value =
                jdbc.queryForObject(
                        "SELECT max(scheduled_at) FROM public.schedule_occurrence "
                                + "WHERE tenant_id = ? AND trigger_config_id = ?",
                        OffsetDateTime.class,
                        context.tenantId(),
                        trigger.id());
        return value == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(value.toInstant());
    }

    private long activeInstanceCount(WorkflowRequestContext context, UUID workflowVersionId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.workflow_instance WHERE tenant_id = ? "
                                + "AND workflow_version_id = ? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                        Long.class,
                        context.tenantId(),
                        workflowVersionId);
        return count == null ? 0 : count;
    }

    private static RuntimeTransitionCommand command(
            WorkflowRequestContext context,
            UUID occurrenceId,
            String command,
            String expectedStatus,
            long expectedVersion,
            String targetStatus,
            String schedulerId,
            String traceId,
            RuntimeTransitionEffects effects) {
        return new RuntimeTransitionCommand(
                context,
                RuntimeEntityType.SCHEDULE,
                occurrenceId,
                command,
                expectedStatus,
                expectedVersion,
                targetStatus,
                RuntimeTransitionActor.SCHEDULER,
                "CRON_" + schedulerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"),
                traceId,
                effects);
    }

    private static RuntimeTransitionEffects fired(UUID instanceId) {
        return new RuntimeTransitionEffects(
                null, null, null, null, instanceId, null, null, null, null);
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record CronTrigger(
            UUID id,
            UUID workflowVersionId,
            Instant createdAt,
            String timezone,
            CronTriggerCalculator.MisfirePolicy misfirePolicy,
            String expression) {}

    private record DueOccurrence(UUID id, long version, Instant scheduledAt, UUID definitionId) {}
}
