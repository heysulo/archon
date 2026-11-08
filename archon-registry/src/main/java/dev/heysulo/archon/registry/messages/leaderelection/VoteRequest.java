package dev.heysulo.archon.registry.messages.leaderelection;

import java.time.Instant;

/** Asks a peer to back the sender as primary for a new term. */
public class VoteRequest extends LeaderElectionMessage {
    private static final long serialVersionUID = 1L;

    public VoteRequest(int rank, long term, String address, Instant startTime) {
        super(rank, term, address, startTime);
    }
}
