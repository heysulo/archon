package dev.heysulo.archon.registry.messages.leaderelection;

import java.time.Instant;

public class LeaderStatusMessage extends LeaderElectionMessage {

    public LeaderStatusMessage(int rank, String address, Instant startTime) {
        super(rank, address, startTime);
    }
}
