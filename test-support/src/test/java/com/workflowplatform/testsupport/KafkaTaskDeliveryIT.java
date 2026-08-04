package com.workflowplatform.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.runtime.OutboxPublicationService;
import com.workflowplatform.controlplane.runtime.OutboxPublicationStore;
import com.workflowplatform.controlplane.runtime.internal.KafkaOutboxEventSink;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.worker.WorkerExecutionService;
import com.workflowplatform.worker.WorkerNodeExecutor;
import com.workflowplatform.worker.WorkerTaskStore;
import com.workflowplatform.worker.internal.KafkaTaskReadyConsumer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class KafkaTaskDeliveryIT {

    private static final String KAFKA_IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    private static final String TASK_TOPIC = "workflow.task.ready.it";
    private static final String EVENTS_TOPIC = "workflow.events.it";
    private static final String GROUP_ID = "workflow-worker-it";
    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c701");
    private static final UUID NODE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c702");
    private static final UUID EVENT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c703");
    private static final UUID FAILED_EVENT_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c704");
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                            DockerImageName.parse(KAFKA_IMAGE)
                                    .asCompatibleSubstituteFor("apache/kafka"))
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    @Test
    void publishesRoutesDeduplicatesAndRetainsFailedOffsets() throws Exception {
        createTopics();
        DefaultKafkaProducerFactory<String, String> producers =
                new DefaultKafkaProducerFactory<>(producerProperties());
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producers);
        try {
            publishOutboxBatch(kafka);
            assertEquals("workflow.instance.started", consumeOne(EVENTS_TOPIC));

            FakeWorkerTaskStore workerStore = new FakeWorkerTaskStore();
            KafkaTaskReadyConsumer listener = workerListener(workerStore);
            KafkaMessageListenerContainer<String, String> container = startContainer(listener);
            try {
                assertEquals("success:" + EVENT_ID, workerStore.awaitSignal());
                awaitCommittedOffset(1L);

                kafka.send(TASK_TOPIC, TENANT_ID.toString(), taskPayload(EVENT_ID, NODE_ID)).get();
                assertEquals("duplicate:" + EVENT_ID, workerStore.awaitSignal());
                awaitCommittedOffset(2L);

                workerStore.fail(FAILED_EVENT_ID);
                kafka.send(TASK_TOPIC, TENANT_ID.toString(), taskPayload(FAILED_EVENT_ID, NODE_ID))
                        .get();
                assertEquals("failure:" + FAILED_EVENT_ID, workerStore.awaitSignal());
                awaitStopped(container);
                assertEquals(2L, committedOffset());
            } finally {
                container.stop();
            }

            workerStore.recover(FAILED_EVENT_ID);
            KafkaMessageListenerContainer<String, String> recovery = startContainer(listener);
            try {
                assertEquals("success:" + FAILED_EVENT_ID, workerStore.awaitSignal());
                awaitCommittedOffset(3L);
            } finally {
                recovery.stop();
            }
            assertEquals(2, workerStore.successfulCompletions());
        } finally {
            producers.destroy();
        }
    }

    private static void publishOutboxBatch(KafkaTemplate<String, String> kafka) {
        FakeOutboxStore store = new FakeOutboxStore();
        store.enqueue(
                new OutboxPublicationStore.ClaimedEvent(
                        EVENT_ID,
                        "workflow.task.ready",
                        TENANT_ID.toString(),
                        taskPayload(EVENT_ID, NODE_ID),
                        TRACE_ID,
                        1,
                        1L));
        store.enqueue(
                new OutboxPublicationStore.ClaimedEvent(
                        UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c705"),
                        "workflow.instance.started",
                        TENANT_ID.toString(),
                        "workflow.instance.started",
                        TRACE_ID,
                        1,
                        1L));
        OutboxPublicationService service = new OutboxPublicationService(store);
        List<OutboxPublicationService.PublicationResult> results =
                service.publishBatch(
                        new WorkflowRequestContext(TENANT_ID, "kafka-it"),
                        "publisher-it",
                        Instant.now(),
                        10,
                        Duration.ofMinutes(1),
                        new KafkaOutboxEventSink(kafka, TASK_TOPIC, EVENTS_TOPIC));

        assertEquals(2, results.size());
        assertTrue(
                results.stream()
                        .allMatch(
                                result ->
                                        result.state()
                                                == OutboxPublicationService.PublicationState
                                                        .PUBLISHED));
        assertEquals(2, store.publishedCount());
    }

    private static KafkaTaskReadyConsumer workerListener(FakeWorkerTaskStore store) {
        WorkerNodeExecutor executor =
                new WorkerNodeExecutor() {
                    @Override
                    public boolean supports(String nodeType) {
                        return "start".equals(nodeType);
                    }

                    @Override
                    public WorkerTaskStore.NodeExecutionResult execute(
                            WorkerTaskStore.ClaimedTask task) {
                        return WorkerTaskStore.NodeExecutionResult.success(
                                "output:" + task.event().eventId(), "2".repeat(64));
                    }
                };
        WorkerExecutionService service =
                new WorkerExecutionService(
                        store, new ObjectMapper(), List.of(executor), GROUP_ID, "worker-it");
        return new KafkaTaskReadyConsumer(service);
    }

    private static KafkaMessageListenerContainer<String, String> startContainer(
            KafkaTaskReadyConsumer listener) {
        ContainerProperties properties = new ContainerProperties(TASK_TOPIC);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener(
                (MessageListener<String, String>) record -> listener.consume(record.value()));
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(
                        new DefaultKafkaConsumerFactory<>(consumerProperties(GROUP_ID)),
                        properties);
        container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        container.start();
        return container;
    }

    private static String consumeOne(String topic) throws Exception {
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProperties("events-observer-it"))) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    String value = records.iterator().next().value();
                    assertNotNull(value);
                    return value;
                }
            }
        }
        throw new IllegalStateException("Kafka event was not observed before the deadline");
    }

    private static void createTopics() throws Exception {
        try (Admin admin = Admin.create(adminProperties())) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(TASK_TOPIC, 1, (short) 1),
                                    new NewTopic(EVENTS_TOPIC, 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        }
    }

    private static void awaitCommittedOffset(long expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (committedOffset() == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(expected, committedOffset());
    }

    private static long committedOffset() throws Exception {
        try (Admin admin = Admin.create(adminProperties())) {
            var offsets =
                    admin.listConsumerGroupOffsets(GROUP_ID)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(TASK_TOPIC, 0));
            return offset == null ? 0L : offset.offset();
        }
    }

    private static void awaitStopped(KafkaMessageListenerContainer<String, String> container)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (container.isRunning() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50L);
        }
        assertFalse(container.isRunning());
    }

    private static Map<String, Object> producerProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return properties;
    }

    private static Map<String, Object> consumerProperties(String groupId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return properties;
    }

    private static Map<String, Object> adminProperties() {
        return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    }

    private static String taskPayload(UUID eventId, UUID nodeId) {
        return """
                {
                  "eventId": "%s",
                  "tenantId": "%s",
                  "eventType": "workflow.task.ready",
                  "schemaVersion": 1,
                  "traceId": "%s",
                  "payload": {"entityId": "%s"}
                }
                """
                .formatted(eventId, TENANT_ID, TRACE_ID, nodeId);
    }

    private static final class FakeOutboxStore implements OutboxPublicationStore {
        private final Queue<ClaimedEvent> events = new ArrayDeque<>();
        private final Set<UUID> published = new HashSet<>();

        void enqueue(ClaimedEvent event) {
            events.add(event);
        }

        int publishedCount() {
            return published.size();
        }

        @Override
        public List<ClaimedEvent> claim(
                WorkflowRequestContext context,
                String publisherId,
                Instant dueAt,
                int limit,
                Duration leaseDuration) {
            List<ClaimedEvent> claimed = new ArrayList<>();
            while (!events.isEmpty() && claimed.size() < limit) {
                claimed.add(events.remove());
            }
            return claimed;
        }

        @Override
        public void markPublished(
                WorkflowRequestContext context,
                UUID eventId,
                String publisherId,
                long expectedVersion) {
            published.add(eventId);
        }

        @Override
        public void releaseForRetry(
                WorkflowRequestContext context,
                UUID eventId,
                String publisherId,
                long expectedVersion,
                Instant retryAt) {
            throw new IllegalStateException("successful Kafka IT must not release an event");
        }
    }

    private static final class FakeWorkerTaskStore implements WorkerTaskStore {
        private final Set<UUID> processed = new HashSet<>();
        private final Set<UUID> failing = new HashSet<>();
        private final BlockingQueue<String> signals = new LinkedBlockingQueue<>();
        private int successfulCompletionCount;

        void fail(UUID eventId) {
            failing.add(eventId);
        }

        void recover(UUID eventId) {
            failing.remove(eventId);
        }

        String awaitSignal() throws InterruptedException {
            String signal = signals.poll(20, TimeUnit.SECONDS);
            assertNotNull(signal);
            return signal;
        }

        int successfulCompletions() {
            return successfulCompletionCount;
        }

        @Override
        public synchronized ClaimResult claim(
                TaskReadyEvent event, String consumerName, String workerInstanceId) {
            if (processed.contains(event.eventId())) {
                signals.add("duplicate:" + event.eventId());
                return ClaimResult.duplicate();
            }
            return ClaimResult.claimed(
                    new ClaimedTask(
                            event,
                            consumerName,
                            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c706"),
                            UUID.randomUUID(),
                            "start",
                            "start",
                            1,
                            "{}",
                            "input-ref",
                            "1".repeat(64),
                            "{}",
                            "dispatch-token",
                            1L,
                            Instant.now().plusSeconds(60)));
        }

        @Override
        public void heartbeat(ClaimedTask task, String workerInstanceId, Duration extension) {
            throw new UnsupportedOperationException("heartbeat is outside this delivery scenario");
        }

        @Override
        public synchronized void complete(
                ClaimedTask task, String workerInstanceId, NodeExecutionResult result) {
            UUID eventId = task.event().eventId();
            if (failing.contains(eventId)) {
                signals.add("failure:" + eventId);
                throw new IllegalStateException("synthetic database commit failure");
            }
            processed.add(eventId);
            successfulCompletionCount++;
            signals.add("success:" + eventId);
        }
    }
}
