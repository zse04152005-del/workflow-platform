package com.workflowplatform.controlplane.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class RuntimeEngineCoreTests {

    private static final UUID ID_ONE = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID ID_TWO = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");
    private static final Instant REFERENCE_INSTANT = Instant.parse("2026-07-31T00:00:00Z");
    private static final String PENDING = "PENDING";
    private static final String CONDITION = "condition";
    private static final String APPROVED = "approved";
    private static final String FALLBACK = "fallback";
    private static final String END = "end";

    @Test
    void dagReadinessRequiresAllPredecessors() {
        DagProgressor progressor = new DagProgressor();
        List<DagProgressor.Decision> decisions =
                progressor.evaluate(
                        List.of(
                                new DagProgressor.Node("start", "SUCCEEDED"),
                                new DagProgressor.Node("work", PENDING)),
                        List.of(new DagProgressor.Edge("start", "work")));
        assertEquals(DagProgressor.DecisionType.READY, decisions.getFirst().type());
    }

    @Test
    void conditionRoutingCancelsOnlyTheUnselectedBranch() {
        DagProgressor progressor = new DagProgressor();
        List<DagProgressor.Decision> decisions =
                progressor.evaluate(
                        List.of(
                                new DagProgressor.Node(CONDITION, "SUCCEEDED"),
                                new DagProgressor.Node(APPROVED, PENDING),
                                new DagProgressor.Node(FALLBACK, PENDING)),
                        List.of(
                                new DagProgressor.Edge(CONDITION, APPROVED, true),
                                new DagProgressor.Edge(CONDITION, FALLBACK, false)));

        assertEquals(DagProgressor.DecisionType.READY, decisions.getFirst().type());
        assertEquals(APPROVED, decisions.getFirst().nodeId());
        assertEquals(DagProgressor.DecisionType.CANCEL, decisions.get(1).type());
        assertEquals(FALLBACK, decisions.get(1).nodeId());
    }

    @Test
    void aSkippedBranchIsNeutralAtAJoin() {
        DagProgressor progressor = new DagProgressor();
        List<DagProgressor.Decision> decisions =
                progressor.evaluate(
                        List.of(
                                new DagProgressor.Node(APPROVED, "SUCCEEDED"),
                                new DagProgressor.Node(FALLBACK, "CANCELLED"),
                                new DagProgressor.Node(END, PENDING)),
                        List.of(
                                new DagProgressor.Edge(APPROVED, END),
                                new DagProgressor.Edge(FALLBACK, END)));

        assertEquals(DagProgressor.DecisionType.READY, decisions.getFirst().type());
        assertEquals(END, decisions.getFirst().nodeId());
    }

    @Test
    void dueTaskScanClaimsOnceAndOrdersDeterministically() {
        DueTaskScanner scanner = new DueTaskScanner();
        Instant now = REFERENCE_INSTANT;
        var tasks =
                List.of(
                        new DueTaskScanner.DueTask(ID_TWO, "READY", now.minusSeconds(1)),
                        new DueTaskScanner.DueTask(ID_ONE, "RETRY_WAIT", now.minusSeconds(2)));
        assertEquals(ID_ONE, scanner.scanAndClaim(tasks, now, 10).getFirst().id());
        assertEquals(0, scanner.scanAndClaim(tasks, now, 10).size());
    }

    @Test
    void outboxAndConsumerSuppressDuplicates() {
        AtomicInteger sends = new AtomicInteger();
        OutboxPublisher publisher = new OutboxPublisher(record -> sends.incrementAndGet());
        var record = new OutboxPublisher.OutboxRecord(ID_ONE, "task.ready", "{}", Instant.EPOCH, 0);
        assertEquals(
                OutboxPublisher.PublishState.SENT,
                publisher.publish(List.of(record), Instant.now()).getFirst().state());
        assertEquals(
                OutboxPublisher.PublishState.DUPLICATE,
                publisher.publish(List.of(record), Instant.now()).getFirst().state());
        assertEquals(1, sends.get());

        TaskEventConsumer consumer = new TaskEventConsumer(event -> sends.incrementAndGet());
        var event = new TaskEventConsumer.TaskEvent(ID_TWO, ID_ONE, ID_TWO, "{}", Instant.EPOCH);
        assertEquals(TaskEventConsumer.ConsumeResult.PROCESSED, consumer.consume(event));
        assertEquals(TaskEventConsumer.ConsumeResult.DUPLICATE, consumer.consume(event));
    }

    @Test
    void staleWorkerGenerationIsRejected() {
        WorkerLeaseService leases = new WorkerLeaseService();
        Instant now = REFERENCE_INSTANT;
        WorkerLeaseService.Lease first = leases.claim(ID_ONE, "worker-a", now, 10);
        leases.release(first.token());
        leases.claim(ID_ONE, "worker-b", now, 10);
        assertThrows(IllegalStateException.class, () -> leases.requireFence(first.token(), now));
    }

    @Test
    void builtinsAndManagedHttpRespectContracts() {
        assertEquals("start", BuiltinNodePlugins.start().type());
        assertTrue(
                BuiltinNodePlugins.condition()
                        .execute(
                                new NodePlugin.NodeExecutionContext(
                                        CONDITION, Map.of(CONDITION, true), Duration.ofSeconds(1)))
                        .success());
        ManagedHttpNodePlugin plugin =
                new ManagedHttpNodePlugin(
                        Set.of("api.example.test"),
                        1024,
                        (uri, input, timeout) ->
                                new ManagedHttpNodePlugin.HttpResponse(
                                        200, "ok".getBytes(StandardCharsets.UTF_8)));
        assertTrue(
                plugin.execute(
                                new NodePlugin.NodeExecutionContext(
                                        "http",
                                        Map.of("endpoint", "https://api.example.test/orders"),
                                        Duration.ofSeconds(5)))
                        .success());
        assertThrows(
                IllegalArgumentException.class, () -> URI.create("https://api.example.test/%zz"));
    }

    @Test
    void cronAndCursorAreStable() {
        CronTriggerCalculator cron = new CronTriggerCalculator();
        Instant next =
                cron.nextOccurrence(
                        Instant.parse("2026-07-30T00:00:00Z"),
                        ZoneId.of("Asia/Shanghai"),
                        Duration.ofHours(24),
                        REFERENCE_INSTANT,
                        CronTriggerCalculator.MisfirePolicy.FIRE_ONCE);
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), next);
        List<CronTriggerCalculator.OccurrenceDecision> catchUp =
                cron.dueOccurrences(
                        "0 * * * * *",
                        ZoneId.of("Asia/Shanghai"),
                        REFERENCE_INSTANT,
                        Instant.parse("2026-07-31T00:03:30Z"),
                        CronTriggerCalculator.MisfirePolicy.CATCH_UP_LIMITED,
                        2);
        List<CronTriggerCalculator.OccurrenceDecision> skipped =
                cron.dueOccurrences(
                        "0 * * * * *",
                        ZoneId.of("America/New_York"),
                        Instant.parse("2026-11-01T05:58:00Z"),
                        Instant.parse("2026-11-01T07:02:00Z"),
                        CronTriggerCalculator.MisfirePolicy.SKIP,
                        1);
        assertEquals(2, catchUp.size());
        assertEquals(CronTriggerCalculator.Decision.FIRE, catchUp.getFirst().decision());
        assertEquals(CronTriggerCalculator.Decision.SKIP, skipped.getFirst().decision());
        assertTrue(cron.overlapAllowed(false, 0));
        assertFalse(cron.overlapAllowed(false, 1));

        RuntimeQueryService query = new RuntimeQueryService();
        RuntimeCursorPage<RuntimeQueryService.RuntimeRow> page =
                query.page(
                        List.of(
                                new RuntimeQueryService.RuntimeRow(
                                        ID_ONE, "instance", "RUNNING", Instant.EPOCH),
                                new RuntimeQueryService.RuntimeRow(
                                        ID_TWO,
                                        "instance",
                                        "RUNNING",
                                        Instant.EPOCH.plusSeconds(1))),
                        null,
                        1);
        assertEquals(ID_TWO, page.items().getFirst().id());
        assertTrue(page.hasMore());
    }
}
