package com.workflowplatform.controlplane.runtime.internal;

import com.workflowplatform.controlplane.runtime.OutboxPublicationService;
import com.workflowplatform.controlplane.runtime.OutboxPublicationStore;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Kafka adapter that routes task-ready and general workflow events to separate topics. */
@Component
@ConditionalOnProperty(prefix = "workflow.kafka", name = "publisher-enabled", havingValue = "true")
public final class KafkaOutboxEventSink implements OutboxPublicationService.EventSink {

    private static final String TASK_READY_EVENT = "workflow.task.ready";

    private final KafkaTemplate<String, String> kafka;
    private final String taskReadyTopic;
    private final String eventsTopic;

    public KafkaOutboxEventSink(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${workflow.kafka.task-ready-topic:workflow.task.ready}")
                    String configuredTaskReadyTopic,
            @Value("${workflow.kafka.events-topic:workflow.events}") String configuredEventsTopic) {
        kafka = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        taskReadyTopic = requireTopic(configuredTaskReadyTopic);
        eventsTopic = requireTopic(configuredEventsTopic);
    }

    @Override
    public void publish(OutboxPublicationStore.ClaimedEvent event) {
        String topic = TASK_READY_EVENT.equals(event.eventType()) ? taskReadyTopic : eventsTopic;
        try {
            kafka.send(topic, event.partitionKey(), event.payload()).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publication was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Kafka publication failed", exception);
        }
    }

    private static String requireTopic(String topic) {
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("Kafka topic has an invalid stable format");
        }
        return topic;
    }
}
