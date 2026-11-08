package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;

import java.time.Instant;

public class LeaderElectionMessage implements Message {
    private static final long serialVersionUID = 3826566345881061502L;

    String address;
    Instant startTime;
    int rank;
    long term;
    // Absent from registries that predate it
    InstanceDetails details;

    public LeaderElectionMessage(int rank, long term, String address, Instant startTime) {
        this.rank = rank;
        this.term = term;
        this.address = address;
        this.startTime = startTime;
    }

    public String getAddress() {
        return address;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public int getRank() {
        return rank;
    }

    /** The highest leadership term the sender knows of. Registries that predate terms send 0. */
    public long getTerm() {
        return term;
    }

    /** Where the sending registry runs, or null from a registry that does not report it. */
    public InstanceDetails getDetails() {
        return details;
    }

    public void setDetails(InstanceDetails details) {
        this.details = details;
    }
}
