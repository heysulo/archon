package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

import java.time.Instant;

public class LeaderElectionMessage implements Message {
    private static final long serialVersionUID = 3826566345881061502L;

    String address;
    Instant startTime;
    int rank;
    long term;

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
}
