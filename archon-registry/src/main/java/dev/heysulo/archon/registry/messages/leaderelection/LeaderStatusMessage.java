package dev.heysulo.archon.registry.messages.leaderelection;

import java.time.Instant;

public class LeaderStatusMessage extends LeaderElectionMessage {
    private static final long serialVersionUID = -8151187921861995658L;

    public LeaderStatusMessage(int rank, long term, String address, Instant startTime) {
        super(rank, term, address, startTime);
    }
}
