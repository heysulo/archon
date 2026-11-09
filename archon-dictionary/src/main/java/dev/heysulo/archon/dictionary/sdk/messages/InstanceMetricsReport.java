package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.services.InstanceMetrics;
import dev.heysulo.databridge.core.common.Message;

/**
 * From an application instance, or a mirror registry, to the primary registry: its latest CPU and memory use, sent
 * every few seconds while connected. The primary keeps the latest with the instance's record for the process list.
 */
public class InstanceMetricsReport implements Message {
    private static final long serialVersionUID = 1L;

    private final InstanceMetrics metrics;

    public InstanceMetricsReport(InstanceMetrics metrics) {
        this.metrics = metrics;
    }

    public InstanceMetrics getMetrics() {
        return metrics;
    }
}
