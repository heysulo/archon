package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import dev.heysulo.archon.dictionary.sdk.messages.LogBatch;
import dev.heysulo.databridge.core.common.Message;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sends captured lines to the primary registry while it asks for them, which it does only while someone watches.
 * Lines go in batches a few times a second, at most {@value #MAX_LINES_PER_BATCH} a batch, so a chatty application
 * cannot flood the registry; lines beyond that are dropped and counted.
 */
public final class LogShipper {
    static final long INTERVAL_MILLIS = 250;
    static final int MAX_LINES_PER_BATCH = 500;

    private final LogBuffer buffer;
    private final Consumer<Message> registry;
    private final ScheduledExecutorService thread;
    private ScheduledFuture<?> task;

    /** @param registry sends to the primary registry; may throw while disconnected */
    public LogShipper(Consumer<Message> registry) {
        this(LogBuffer.getInstance(), registry, Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("archon-log-shipper", true)));
    }

    LogShipper(LogBuffer buffer, Consumer<Message> registry, ScheduledExecutorService thread) {
        this.buffer = buffer;
        this.registry = registry;
        this.thread = thread;
    }

    /** The registry asked for lines: sends those kept from before, then new ones as they come. */
    public synchronized void start() {
        if (task != null) {
            return;
        }
        List<LogLine> history = buffer.startStreaming();
        thread.execute(() -> send(new LogBatch(history, 0, true)));
        task = thread.scheduleWithFixedDelay(this::ship, INTERVAL_MILLIS, INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Nobody is watching, or the registry connection dropped: a new primary asks again if anyone watches. */
    public synchronized void stop() {
        if (task == null) {
            return;
        }
        task.cancel(false);
        task = null;
        buffer.stopStreaming();
    }

    public synchronized boolean isRunning() {
        return task != null;
    }

    void ship() {
        LogBuffer.Drained drained = buffer.drain(MAX_LINES_PER_BATCH);
        if (!drained.lines().isEmpty() || drained.dropped() > 0) {
            send(new LogBatch(drained.lines(), drained.dropped(), false));
        }
    }

    private void send(LogBatch batch) {
        try {
            registry.accept(batch);
        } catch (RuntimeException e) {
            // Disconnected: the lines are lost, and streaming stops when the SDK notices the drop
        }
    }
}
