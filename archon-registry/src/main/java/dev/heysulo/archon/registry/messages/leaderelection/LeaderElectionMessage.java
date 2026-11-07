package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

import java.time.Instant;

public class LeaderElectionMessage implements Message {
    String address;
    Instant startTime;
    int rank;

    public LeaderElectionMessage(int rank, String address, Instant startTime) {
        this.rank = rank;
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
}
