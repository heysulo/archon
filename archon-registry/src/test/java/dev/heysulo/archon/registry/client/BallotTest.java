package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Candidate;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class BallotTest {

    private static final Instant EARLY = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-01-01T00:01:00Z");
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final Candidate VOTER = new Candidate(0, LATE, "voter");

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private Ballot ballot;

    @BeforeMethod
    public void freshBallot() {
        ballot = new Ballot(LEASE, clock::get);
    }

    private static Candidate fresh(String address) {
        return new Candidate(0, EARLY, address);
    }

    private boolean consider(Candidate candidate, long term) {
        return ballot.consider(candidate, term, 0, false, List.of(VOTER));
    }

    @Test
    public void votesOncePerTerm() {
        Assert.assertTrue(consider(fresh("a"), 1));
        Assert.assertFalse(consider(fresh("b"), 1));
        Assert.assertTrue(consider(fresh("a"), 1), "the same candidate may ask again");
    }

    @Test
    public void keepsBackingItsCandidateForTheLease() {
        Assert.assertTrue(consider(fresh("a"), 1));
        Assert.assertFalse(consider(fresh("b"), 2));
        clock.addAndGet(LEASE.toMillis());
        Assert.assertTrue(consider(fresh("b"), 2));
    }

    @Test
    public void refusesTermsAlreadyWonOrPassed() {
        Assert.assertFalse(ballot.consider(fresh("a"), 3, 3, false, List.of(VOTER)));
        Assert.assertTrue(consider(fresh("a"), 5));
        clock.addAndGet(LEASE.toMillis());
        Assert.assertFalse(consider(fresh("b"), 4));
    }

    @Test
    public void refusesWhileItHasAPrimary() {
        Assert.assertFalse(ballot.consider(fresh("a"), 1, 0, true, List.of(VOTER)));
    }

    @Test
    public void refusesCandidatesItWouldNotElect() {
        Candidate lowerMirror = new Candidate(2, EARLY, "voter");
        Assert.assertFalse(ballot.consider(new Candidate(3, EARLY, "a"), 1, 0, false, List.of(lowerMirror)));
        Assert.assertFalse(ballot.consider(new Candidate(0, LATE, "a"), 1, 0, false,
                List.of(new Candidate(0, EARLY, "voter"))), "an older fresh instance should lead instead");
    }

    @Test
    public void aCandidateThatVotedForItselfCannotBackARivalInTheSameTerm() {
        Assert.assertTrue(ballot.voteForSelf("voter", 1));
        clock.addAndGet(LEASE.toMillis());
        Assert.assertFalse(consider(fresh("a"), 1));
        Assert.assertTrue(consider(fresh("a"), 2));
    }

    @Test
    public void twoCandidatesNeverBothWinAMajorityOfThreeInOneTerm() {
        Ballot voter = new Ballot(LEASE, clock::get);
        Ballot a = new Ballot(LEASE, clock::get);
        Ballot c = new Ballot(LEASE, clock::get);
        Assert.assertTrue(a.voteForSelf("a", 1));
        Assert.assertTrue(c.voteForSelf("c", 1));
        List<Candidate> view = List.of(new Candidate(0, LATE, "b"));
        boolean votedA = voter.consider(fresh("a"), 1, 0, false, view);
        boolean votedC = voter.consider(fresh("c"), 1, 0, false, view);
        Assert.assertTrue(votedA ^ votedC);
    }
}
