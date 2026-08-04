package com.workflowplatform.worker.internal;

import com.workflowplatform.worker.WorkerExecutionService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka consumer entry point; offsets advance only after the fenced database result commits. */
@Component
@ConditionalOnProperty(prefix = "workflow.worker", name = "kafka-enabled", havingValue = "true")
public final class KafkaTaskReadyConsumer {

    private final WorkerExecutionService executionService;

    public KafkaTaskReadyConsumer(WorkerExecutionService workerExecutionService) {
        executionService = Objects.requireNonNull(workerExecutionService, "workerExecutionService");
    }

    @KafkaListener(
            topics = "${workflow.kafka.task-ready-topic:workflow.task.ready}",
            groupId = "${workflow.worker.consumer-name:workflow-worker}")
    public void consume(String payload) {
        executionService.process(payload);
    }
}
