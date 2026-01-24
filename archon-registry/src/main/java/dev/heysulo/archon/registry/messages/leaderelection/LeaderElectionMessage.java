package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

import java.time.Instant;

public class LeaderElectionMessage implements Message {
    String address;
    Instant startTime;

    public LeaderElectionMessage(String address, Instant startTime) {
        this.address = address;
        this.startTime = startTime;
    }

    public String getAddress() {
        return address;
    }

    public Instant getStartTime() {
        return startTime;
    }
}
