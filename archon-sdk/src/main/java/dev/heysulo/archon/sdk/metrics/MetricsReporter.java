package dev.heysulo.archon.sdk.metrics;

import dev.heysulo.archon.dictionary.sdk.messages.InstanceMetricsReport;
import dev.heysulo.databridge.core.common.Message;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reports this process's CPU and memory use to the primary registry every {@link #INTERVAL}, for the portal's
 * process list. A report that cannot be sent, as while reconnecting, is skipped; the next one follows.
 */
public final class MetricsReporter {
    public static final Duration INTERVAL = Duration.ofSeconds(5);

    private final MetricsSampler sampler;
    private final Consumer<Message> registry;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("archon-metrics", true));
    private boolean started;

    /** @param registry sends to the primary registry; may throw while disconnected */
    public MetricsReporter(Consumer<Message> registry) {
        this(new MetricsSampler(), registry);
    }

    MetricsReporter(MetricsSampler sampler, Consumer<Message> registry) {
        this.sampler = sampler;
        this.registry = registry;
    }

    /** Starts reporting, once; the first report goes at once. */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        thread.scheduleWithFixedDelay(this::report, 0, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        thread.shutdownNow();
    }

    void report() {
        try {
            registry.accept(new InstanceMetricsReport(sampler.sample()));
        } catch (RuntimeException e) {
            // Not connected right now: the next report goes once it is
        }
    }
}
