package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

public class MirrorRegistrationTest {

    private static final int ROUNDS = 200;

    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    // A new primary sets its rank before it creates its own record, so a mirror joining in that gap must not be
    // handed rank 1 and believe it is primary too.
    @Test
    public void mirrorIsNeverGivenThePrimaryRank() {
        FakeConnection mirror = new FakeConnection();
        server.onMessage(null, mirror,
                new LeaderElectionMessage(RANK_FORECASTED_PRIMARY, 0, "10.0.0.2", Instant.now()));

        Assert.assertTrue(mirror.last(RegistryRegistrationResponse.class).getRank() >= RANK_MIRROR);
    }

    @Test
    public void mirrorsJoiningTogetherGetDistinctRanks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                FakeConnection first = new FakeConnection();
                FakeConnection second = new FakeConnection();
                CyclicBarrier start = new CyclicBarrier(2);
                CompletableFuture<Void> a = CompletableFuture.runAsync(() -> join(start, first), pool);
                CompletableFuture<Void> b = CompletableFuture.runAsync(() -> join(start, second), pool);
                CompletableFuture.allOf(a, b).get();

                int firstRank = first.last(RegistryRegistrationResponse.class).getRank();
                int secondRank = second.last(RegistryRegistrationResponse.class).getRank();
                Assert.assertNotEquals(firstRank, secondRank, "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void join(CyclicBarrier start, FakeConnection connection) {
        try {
            start.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        server.onMessage(null, connection,
                new LeaderElectionMessage(RANK_FORECASTED_PRIMARY, 0, "10.0.0.1", Instant.now()));
    }
}
