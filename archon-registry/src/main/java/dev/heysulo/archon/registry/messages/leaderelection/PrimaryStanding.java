package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

import java.time.Instant;
import java.util.Comparator;

/**
 * A primary's standing, exchanged when two primaries find each other after a partition heals. Every mirror backs the
 * primary it follows, so a primary's supporters are its votes, its own included. Both primaries compare the same two
 * standings, so they agree on the winner and the other terminates.
 */
public class PrimaryStanding implements Message {
    private static final long serialVersionUID = 1L;

    // More supporters, then the newer term, then the older instance, then the address, so no two standings tie
    private static final Comparator<PrimaryStanding> STRONGEST_FIRST = Comparator
            .comparingInt(PrimaryStanding::getSupporters).reversed()
            .thenComparing(Comparator.comparingLong(PrimaryStanding::getTerm).reversed())
            .thenComparing(PrimaryStanding::getStartTime)
            .thenComparing(PrimaryStanding::getAddress);

    private final String address;
    private final Instant startTime;
    private final long term;
    private final int supporters;
    private final boolean reply;

    /**
     * @param supporters this primary and the mirrors following it
     * @param reply      whether this answers a rival's standing, so the rival does not answer it in turn
     */
    public PrimaryStanding(String address, Instant startTime, long term, int supporters, boolean reply) {
        this.address = address;
        this.startTime = startTime;
        this.term = term;
        this.supporters = supporters;
        this.reply = reply;
    }

    public boolean beats(PrimaryStanding rival) {
        return STRONGEST_FIRST.compare(this, rival) < 0;
    }

    public String getAddress() {
        return address;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public long getTerm() {
        return term;
    }

    public int getSupporters() {
        return supporters;
    }

    public boolean isReply() {
        return reply;
    }

    @Override
    public String toString() {
        return String.format("%s (term %d, %d supporters)", address, term, supporters);
    }
}
