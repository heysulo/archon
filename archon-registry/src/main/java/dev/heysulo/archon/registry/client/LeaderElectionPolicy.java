package dev.heysulo.archon.registry.client;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;

import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

/**
 * Decides who leads the registry cluster.
 *
 * <p>Only established mirrors hold synced application state, so a live mirror always beats a freshly started
 * instance: letting an empty instance lead would make every application's rank unknown. The rules, in order:
 * <ol>
 *     <li>If any peer is primary, follow it.</li>
 *     <li>An established mirror leads unless a lower-ranked mirror is alive.</li>
 *     <li>A fresh instance never leads while an established mirror is alive.</li>
 *     <li>When every instance is fresh, the oldest leads.</li>
 * </ol>
 * A winner still needs a majority of the configured registries in view, and then a majority of votes.
 */
public final class LeaderElectionPolicy {

    private static final Comparator<Candidate> OLDEST_FIRST =
            Comparator.comparing(Candidate::startTime).thenComparing(Candidate::address);

    public enum Decision {
        FOLLOW_PRIMARY,
        LEAD,
        WAIT
    }

    public record Candidate(int rank, Instant startTime, String address) {
        boolean isPrimary() {
            return rank == RANK_PRIMARY;
        }

        boolean isEstablishedMirror() {
            return rank >= RANK_MIRROR;
        }

        boolean isFresh() {
            return rank == RANK_FORECASTED_PRIMARY;
        }
    }

    private LeaderElectionPolicy() {
    }

    /**
     * Applies the rules, leading only when this instance and its peers make up a majority of all registries.
     */
    public static Decision decide(Candidate self, Collection<Candidate> peers, int quorum) {
        Decision decision = decide(self, peers);
        return decision == Decision.LEAD && peers.size() + 1 < quorum ? Decision.WAIT : decision;
    }

    public static Decision decide(Candidate self, Collection<Candidate> peers) {
        if (peers.stream().anyMatch(Candidate::isPrimary)) {
            return Decision.FOLLOW_PRIMARY;
        }
        if (self.isEstablishedMirror()) {
            return peers.stream().anyMatch(peer -> peer.isEstablishedMirror() && peer.rank() < self.rank())
                    ? Decision.WAIT
                    : Decision.LEAD;
        }
        if (peers.stream().anyMatch(Candidate::isEstablishedMirror)) {
            return Decision.WAIT;
        }
        boolean olderPeerExists = peers.stream()
                .filter(Candidate::isFresh)
                .anyMatch(peer -> OLDEST_FIRST.compare(peer, self) < 0);
        return olderPeerExists ? Decision.WAIT : Decision.LEAD;
    }
}
