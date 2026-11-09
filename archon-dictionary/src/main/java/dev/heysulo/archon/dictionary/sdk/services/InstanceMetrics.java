package dev.heysulo.archon.dictionary.sdk.services;

import java.io.Serial;
import java.io.Serializable;

/**
 * How much CPU and memory an application instance uses, as it measured itself. A value it could not measure is
 * {@link #UNKNOWN}.
 *
 * @param sampledAt   when it was measured, in milliseconds since the epoch, by the instance's clock
 * @param cpuLoad     the process's share of the CPUs available to it over the last moments, from 0 to 1
 * @param processors  how many CPUs are available to it, which in a container is its CPU limit
 * @param rssBytes    the process's resident memory, on Linux; {@link #UNKNOWN} elsewhere
 * @param memoryLimit the memory available to it, which in a container is its memory limit
 * @param heapUsed    Java heap in use
 * @param heapMax     the most heap the JVM will use, or {@link #UNKNOWN} if unbounded
 * @param nonHeapUsed memory the JVM uses outside the heap, such as class metadata and compiled code
 * @param threads     live threads
 * @param uptimeMillis how long the JVM has run
 */
public record InstanceMetrics(long sampledAt, double cpuLoad, int processors, long rssBytes, long memoryLimit,
                              long heapUsed, long heapMax, long nonHeapUsed, int threads, long uptimeMillis)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    public static final long UNKNOWN = -1;
}
