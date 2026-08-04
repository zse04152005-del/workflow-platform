package com.workflowplatform.worker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Keeps the non-web Worker process alive and provides a bounded graceful-stop boundary. */
@Component
public final class WorkerRuntimeLifecycle implements SmartLifecycle {

    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final ExecutorService runtimeThread =
            Executors.newSingleThreadExecutor(
                    runnable ->
                            Thread.ofPlatform()
                                    .name("workflow-worker-runtime")
                                    .unstarted(runnable));

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            runtimeThread.execute(this::awaitStop);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            stopSignal.countDown();
            runtimeThread.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void awaitStop() {
        try {
            stopSignal.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
