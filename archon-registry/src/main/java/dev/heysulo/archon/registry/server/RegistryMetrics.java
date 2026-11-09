package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.sdk.metrics.MetricsReporter;
import dev.heysulo.archon.sdk.metrics.MetricsSampler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Measures this registry's CPU and memory with the SDK's sampler, as often as applications report theirs, so
 * registries show them in the portal too.
 */
public final class RegistryMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryMetrics.class);

    private RegistryMetrics() {
    }

    public static void start() {
        MetricsSampler sampler = new MetricsSampler();
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("registry-metrics", true));
        thread.scheduleWithFixedDelay(() -> {
            try {
                RegistryServer.reportOwnMetrics(sampler.sample());
            } catch (RuntimeException e) {
                LOGGER.debug("Cannot report this registry's metrics: {}", e.getMessage());
            }
        }, 0, MetricsReporter.INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }
}
