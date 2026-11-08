package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Candidate;
import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Decision;

import java.time.Duration;
import java.util.Collection;
import java.util.function.LongSupplier;

/**
 * One registry's vote. A registry votes at most once per term, so two candidates can never both collect a majority
 * for the same term.
 */
public final class Ballot {
    private static final String NOBODY = "";

    private final long leaseMillis;
    private final LongSupplier clockMillis;
    private long votedTerm;
    private String votedFor = NOBODY;
    private long votedAtMillis;

    /**
     * @param lease       how long a vote keeps backing its candidate before the voter may back someone else
     * @param clockMillis a monotonic clock
     */
    public Ballot(Duration lease, LongSupplier clockMillis) {
        this.leaseMillis = lease.toMillis();
        this.clockMillis = clockMillis;
        this.votedAtMillis = clockMillis.getAsLong() - leaseMillis;
    }

    /**
     * Decides whether to back a candidate for a term.
     *
     * @param knownTerm    the highest term this voter has seen won
     * @param primaryKnown whether this voter is still connected to a primary
     * @param view         the registries this voter can see, itself included and the candidate excluded
     */
    public synchronized boolean consider(Candidate candidate, long term, long knownTerm, boolean primaryKnown,
            Collection<Candidate> view) {
        if (primaryKnown || term <= knownTerm || !mayBack(candidate.address(), term)) {
            return false;
        }
        if (LeaderElectionPolicy.decide(candidate, view) != Decision.LEAD) {
            return false;
        }
        record(candidate.address(), term);
        return true;
    }

    /** Votes for this registry itself, as a candidate does before asking its peers. */
    public synchronized boolean voteForSelf(String selfAddress, long term) {
        if (!mayBack(selfAddress, term)) {
            return false;
        }
        record(selfAddress, term);
        return true;
    }

    public synchronized long getVotedTerm() {
        return votedTerm;
    }

    private boolean mayBack(String address, long term) {
        boolean sameCandidate = votedFor.equals(address);
        if (term < votedTerm || term == votedTerm && !sameCandidate) {
            return false;
        }
        return sameCandidate || clockMillis.getAsLong() - votedAtMillis >= leaseMillis;
    }

    private void record(String address, long term) {
        votedFor = address;
        votedTerm = term;
        votedAtMillis = clockMillis.getAsLong();
    }
}
