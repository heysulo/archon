package dev.heysulo.archon.dictionary.sdk.services;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * One application instance as the primary registry sees it.
 *
 * @param runLevel  the instance's lifecycle state, such as RUNNING or KILLED
 * @param running   whether that state counts as running
 * @param connected whether the instance's connection to the registry is open
 * @param services  the services it serves, as {@code id@host:port}
 * @param details   where it runs, or null if unknown
 */
public record ProcessInfo(String group, String name, int rank, String runLevel, boolean running, boolean connected,
                          List<String> services, InstanceDetails details) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public ProcessInfo {
        services = List.copyOf(services);
    }
}
