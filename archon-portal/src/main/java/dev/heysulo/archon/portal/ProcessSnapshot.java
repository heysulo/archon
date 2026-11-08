package dev.heysulo.archon.portal;

import dev.heysulo.archon.dictionary.sdk.services.ProcessInfo;

import java.time.Instant;
import java.util.List;

/**
 * What the portal shows: every process the primary registry knows, which registry answered, and which portal
 * replica asked.
 *
 * @param registry    the primary registry's address
 * @param term        that primary's leadership term
 * @param generatedAt when the registry was asked
 * @param servedBy    the portal replica that answered this request
 */
public record ProcessSnapshot(String registry, long term, String generatedAt, ServedBy servedBy,
                              List<ProcessInfo> processes) {

    /** A portal replica, its rank, and whether it is the primary or a mirror. */
    public record ServedBy(String host, int rank, String role) {
    }

    public ProcessSnapshot {
        processes = List.copyOf(processes);
    }

    static ProcessSnapshot of(String registry, long term, List<ProcessInfo> processes) {
        return new ProcessSnapshot(registry, term, Instant.now().toString(), null, processes);
    }

    /** Whether both show the same registry, term and processes, whenever they were taken. */
    boolean sameContent(ProcessSnapshot other) {
        return registry.equals(other.registry) && term == other.term && processes.equals(other.processes);
    }

    ProcessSnapshot servedBy(PortalIdentity identity) {
        return new ProcessSnapshot(registry, term, generatedAt,
                new ServedBy(identity.getHost(), identity.getRank(), identity.getRole()), processes);
    }
}
