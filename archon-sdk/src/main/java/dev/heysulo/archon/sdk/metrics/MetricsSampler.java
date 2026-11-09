package dev.heysulo.archon.sdk.metrics;

import dev.heysulo.archon.dictionary.sdk.services.InstanceMetrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Measures this process's CPU and memory use through the JVM's management beans, which are container aware: the CPUs
 * and memory available are the container's limits. Resident memory comes from {@code /proc/self/status}, on Linux.
 */
public final class MetricsSampler {
    private static final Path STATUS = Path.of("/proc/self/status");
    private static final long KILOBYTE = 1024;

    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    /** Measures now. Never throws: a figure that cannot be read is {@link InstanceMetrics#UNKNOWN}. */
    public InstanceMetrics sample() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        return new InstanceMetrics(System.currentTimeMillis(), cpuLoad(), os.getAvailableProcessors(),
                residentBytes(STATUS), memoryLimit(), heap.getUsed(), heap.getMax(), nonHeap.getUsed(),
                ManagementFactory.getThreadMXBean().getThreadCount(), ManagementFactory.getRuntimeMXBean().getUptime());
    }

    private double cpuLoad() {
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double load = sun.getProcessCpuLoad();
            // Negative until the JVM has measured once
            return load < 0 ? InstanceMetrics.UNKNOWN : load;
        }
        return InstanceMetrics.UNKNOWN;
    }

    private long memoryLimit() {
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            long total = sun.getTotalMemorySize();
            return total > 0 ? total : InstanceMetrics.UNKNOWN;
        }
        return InstanceMetrics.UNKNOWN;
    }

    /** Reads {@code VmRSS} from a status file in the format of {@code /proc/self/status}. */
    static long residentBytes(Path status) {
        if (!Files.isReadable(status)) {
            return InstanceMetrics.UNKNOWN;
        }
        try {
            List<String> lines = Files.readAllLines(status, StandardCharsets.US_ASCII);
            for (String line : lines) {
                if (line.startsWith("VmRSS:")) {
                    // VmRSS:    123456 kB
                    String[] parts = line.substring("VmRSS:".length()).trim().split("\\s+");
                    return Long.parseLong(parts[0]) * KILOBYTE;
                }
            }
        } catch (IOException | RuntimeException e) {
            return InstanceMetrics.UNKNOWN;
        }
        return InstanceMetrics.UNKNOWN;
    }
}
