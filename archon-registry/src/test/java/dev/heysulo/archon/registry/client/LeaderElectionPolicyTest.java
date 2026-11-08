package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Candidate;
import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Decision;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

public class LeaderElectionPolicyTest {

    private static final Instant EARLY = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-01-01T00:01:00Z");

    private static Candidate fresh(Instant startTime, String address) {
        return new Candidate(0, startTime, address);
    }

    private static Candidate ranked(int rank, String address) {
        return new Candidate(rank, EARLY, address);
    }

    @Test
    public void loneInstanceLeads() {
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(EARLY, "a"), List.of()), Decision.LEAD);
    }

    @Test
    public void anyInstanceFollowsALivePrimary() {
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(EARLY, "a"), List.of(ranked(1, "b"))),
                Decision.FOLLOW_PRIMARY);
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(2, "a"), List.of(ranked(1, "b"))),
                Decision.FOLLOW_PRIMARY);
    }

    @Test
    public void restartedInstanceNeverBeatsLiveMirrors() {
        Assert.assertEquals(
                LeaderElectionPolicy.decide(fresh(LATE, "a"), List.of(ranked(2, "b"), ranked(3, "c"))),
                Decision.WAIT);
    }

    @Test
    public void lowestRankedMirrorLeads() {
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(2, "a"), List.of(ranked(3, "b"))), Decision.LEAD);
    }

    @Test
    public void higherRankedMirrorWaitsForLowerOne() {
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(3, "a"), List.of(ranked(2, "b"))), Decision.WAIT);
    }

    @Test
    public void mirrorLeadsOverRestartedInstance() {
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(3, "a"), List.of(fresh(EARLY, "b"))), Decision.LEAD);
    }

    @Test
    public void lastSurvivingMirrorLeadsWhateverItsRank() {
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(5, "a"), List.of()), Decision.LEAD);
    }

    @Test
    public void oldestFreshInstanceLeadsAfterFullRestart() {
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(EARLY, "a"), List.of(fresh(LATE, "b"))), Decision.LEAD);
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(LATE, "b"), List.of(fresh(EARLY, "a"))), Decision.WAIT);
    }

    @Test
    public void equalStartTimesAreBrokenByAddressSoExactlyOneLeads() {
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(EARLY, "a"), List.of(fresh(EARLY, "b"))), Decision.LEAD);
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(EARLY, "b"), List.of(fresh(EARLY, "a"))), Decision.WAIT);
    }

    @Test
    public void everyTwoMirrorViewElectsExactlyOneLeader() {
        Candidate low = ranked(2, "a");
        Candidate high = ranked(3, "b");
        long leaders = List.of(
                LeaderElectionPolicy.decide(low, List.of(high)),
                LeaderElectionPolicy.decide(high, List.of(low))).stream().filter(Decision.LEAD::equals).count();
        Assert.assertEquals(leaders, 1L);
    }

    @Test
    public void noOneLeadsWithoutAMajorityInView() {
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(2, "a"), List.of(), 2), Decision.WAIT);
        Assert.assertEquals(LeaderElectionPolicy.decide(ranked(2, "a"), List.of(ranked(3, "b")), 2), Decision.LEAD);
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(EARLY, "a"), List.of(), 1), Decision.LEAD);
    }

    @Test
    public void followingAPrimaryNeedsNoMajority() {
        Assert.assertEquals(LeaderElectionPolicy.decide(fresh(LATE, "a"), List.of(ranked(1, "b")), 3),
                Decision.FOLLOW_PRIMARY);
    }
}
