package dev.heysulo.archon.sdk.metrics;

import dev.heysulo.archon.dictionary.sdk.messages.InstanceMetricsReport;
import dev.heysulo.archon.dictionary.sdk.services.InstanceMetrics;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MetricsSamplerTest {

    @Test
    public void measuresThisProcess() {
        InstanceMetrics metrics = new MetricsSampler().sample();
        Assert.assertTrue(metrics.processors() > 0);
        Assert.assertTrue(metrics.heapUsed() > 0);
        Assert.assertTrue(metrics.threads() > 0);
        Assert.assertTrue(metrics.uptimeMillis() >= 0);
        Assert.assertTrue(metrics.cpuLoad() == InstanceMetrics.UNKNOWN
                || metrics.cpuLoad() >= 0 && metrics.cpuLoad() <= 1, "a share of the CPUs: " + metrics.cpuLoad());
    }

    @Test
    public void readsResidentMemoryFromProcStatus() throws Exception {
        Path status = Files.createTempFile("status", "");
        Files.writeString(status, "Name:\tjava\nVmPeak:\t  999 kB\nVmRSS:\t   123456 kB\nThreads:\t30\n");
        Assert.assertEquals(MetricsSampler.residentBytes(status), 123456L * 1024);
        Files.writeString(status, "Name:\tjava\n");
        Assert.assertEquals(MetricsSampler.residentBytes(status), InstanceMetrics.UNKNOWN);
        Assert.assertEquals(MetricsSampler.residentBytes(status.resolveSibling("missing-status")),
                InstanceMetrics.UNKNOWN, "not Linux");
    }

    @Test
    public void skipsReportsThatCannotBeSent() {
        List<Message> sent = new ArrayList<>();
        boolean[] connected = {false};
        MetricsReporter reporter = new MetricsReporter(new MetricsSampler(), message -> {
            if (!connected[0]) {
                throw new IllegalStateException("Connection unavailable");
            }
            sent.add(message);
        });
        reporter.report();
        Assert.assertTrue(sent.isEmpty());
        connected[0] = true;
        reporter.report();
        Assert.assertTrue(sent.get(0) instanceof InstanceMetricsReport);
        reporter.stop();
    }
}
