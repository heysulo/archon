package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.PrimaryStanding;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import dev.heysulo.archon.registry.messages.leaderelection.VoteRequest;
import dev.heysulo.archon.registry.messages.leaderelection.VoteResponse;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Instant;

import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

public class PrimaryTermTest {

    private static final long TERM = 7;

    private RegistryServer server;

    @BeforeClass
    public void actAsPrimaryInATerm() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
        RegistryServer.observeTerm(TERM);
    }

    @Test
    public void primaryNeverBacksARival() {
        FakeConnection rival = new FakeConnection();
        server.onMessage(null, rival, new VoteRequest(2, TERM + 1, "10.0.0.3", Instant.now()));

        VoteResponse response = rival.last(VoteResponse.class);
        Assert.assertFalse(response.isGranted());
        Assert.assertEquals(response.getTerm(), TERM + 1);
        Assert.assertTrue(response.getKnownTerm() >= TERM);
    }

    @Test
    public void primaryReportsItsTermToPeersMirrorsAndApplications() {
        FakeConnection peer = new FakeConnection();
        server.onMessage(null, peer, new LeaderElectionMessage(RANK_FORECASTED_PRIMARY, 0, "10.0.0.4", Instant.now()));
        Assert.assertTrue(peer.last(LeaderStatusMessage.class).getTerm() >= TERM);
        Assert.assertTrue(peer.last(RegistryRegistrationResponse.class).getTerm() >= TERM);

        FakeConnection application = new FakeConnection();
        server.onMessage(null, application, new PrimaryRegistryLookupMessage());
        Assert.assertTrue(application.last(PrimaryRegistryLookupResponseMessage.class).getTerm() >= TERM);
    }

    // A primary that was partitioned off has no mirrors left. Meeting it, this primary wins with its own vote plus
    // a mirror's, answers with its standing, and moves past the loser's term so the loser's applications accept it.
    @Test
    public void primaryWinsTheVoteAgainstARivalWithFewerSupporters() {
        server.onMessage(null, new FakeConnection(),
                new LeaderElectionMessage(RANK_MIRROR, RegistryServer.getTerm(), "10.0.0.5", Instant.now()));
        long rivalTerm = RegistryServer.getTerm() + 3;
        FakeConnection rival = new FakeConnection();
        server.onMessage(null, rival, new PrimaryStanding("10.0.0.6", Instant.EPOCH, rivalTerm, 1, false));

        PrimaryStanding answer = rival.last(PrimaryStanding.class);
        Assert.assertTrue(answer.isReply());
        Assert.assertTrue(answer.getSupporters() >= 2);
        Assert.assertEquals(RegistryServer.getRank(), RANK_PRIMARY);
        Assert.assertEquals(RegistryServer.getTerm(), rivalTerm + 1);
    }

    @Test
    public void primaryDoesNotAnswerARivalsReply() {
        FakeConnection rival = new FakeConnection();
        server.onMessage(null, rival, new PrimaryStanding("10.0.0.7", Instant.EPOCH, 0, 1, true));
        Assert.assertNull(rival.last(PrimaryStanding.class));
    }

    @Test
    public void primaryMovesPastANewerTermItLearnsOf() {
        long newer = RegistryServer.getTerm() + 2;
        server.onMessage(null, new FakeConnection(),
                new LeaderElectionMessage(RANK_MIRROR, newer, "10.0.0.8", Instant.now()));
        Assert.assertEquals(RegistryServer.getRank(), RANK_PRIMARY);
        Assert.assertEquals(RegistryServer.getTerm(), newer + 1);
    }
}
