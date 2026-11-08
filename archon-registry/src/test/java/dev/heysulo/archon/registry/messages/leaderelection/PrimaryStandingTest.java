package dev.heysulo.archon.registry.messages.leaderelection;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

public class PrimaryStandingTest {

    private static final Instant OLDER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    public void moreSupportersWinsWhateverTheTerm() {
        assertWinner(standing("10.0.0.2", NEWER, 2, 2), standing("10.0.0.1", OLDER, 9, 1));
    }

    @Test
    public void equalSupportersFallBackToTheNewerTerm() {
        assertWinner(standing("10.0.0.2", NEWER, 3, 1), standing("10.0.0.1", OLDER, 2, 1));
    }

    @Test
    public void equalTermsFallBackToTheOlderInstance() {
        assertWinner(standing("10.0.0.2", OLDER, 3, 1), standing("10.0.0.1", NEWER, 3, 1));
    }

    @Test
    public void identicalStandingsFallBackToTheAddress() {
        assertWinner(standing("10.0.0.1", OLDER, 3, 1), standing("10.0.0.2", OLDER, 3, 1));
    }

    // Both primaries judge the same pair, so exactly one of them may win, or both would stay or both would quit
    @Test
    public void exactlyOneOfTwoPrimariesWins() {
        List<PrimaryStanding> standings = List.of(
                standing("10.0.0.1", OLDER, 1, 1), standing("10.0.0.1", NEWER, 2, 2),
                standing("10.0.0.2", OLDER, 2, 1), standing("10.0.0.2", NEWER, 1, 2),
                standing("10.0.0.3", OLDER, 2, 2), standing("10.0.0.3", NEWER, 1, 1));
        for (PrimaryStanding a : standings) {
            for (PrimaryStanding b : standings) {
                if (!a.getAddress().equals(b.getAddress())) {
                    Assert.assertNotEquals(a.beats(b), b.beats(a), a + " vs " + b);
                }
            }
        }
    }

    private static void assertWinner(PrimaryStanding winner, PrimaryStanding loser) {
        Assert.assertTrue(winner.beats(loser));
        Assert.assertFalse(loser.beats(winner));
    }

    private static PrimaryStanding standing(String address, Instant startTime, long term, int supporters) {
        return new PrimaryStanding(address, startTime, term, supporters, false);
    }
}
