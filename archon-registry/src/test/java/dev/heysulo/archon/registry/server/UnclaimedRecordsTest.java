package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

public class UnclaimedRecordsTest {

    private final ApplicationManager applications = ApplicationManager.getInstance();
    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    // A promoted mirror inherits states but no connections. The leader died during the failover, so it will never
    // disconnect from this registry; the sweep retires it and hands its rank to a standby that did reconnect.
    @Test
    public void aLeaderThatNeverReconnectedIsRetiredAndAStandbyLeads() {
        Application deadLeader = inherited("Sweep", "DeadLeaderApp", 1);
        Application deadStandby = inherited("Sweep", "DeadLeaderApp", 2);
        Application liveStandby = inherited("Sweep", "DeadLeaderApp", 3);
        FakeConnection reconnected = new FakeConnection();
        liveStandby.setConnection(reconnected);

        server.retireUnclaimedRecords();

        Assert.assertEquals(deadLeader.getRunLevel(), RunLevel.KILLED);
        Assert.assertEquals(deadStandby.getRunLevel(), RunLevel.KILLED);
        Assert.assertEquals(liveStandby.getRank(), RANK_PRIMARY);
        Assert.assertEquals(reconnected.last(ApplicationRankUpdate.class).getRank(), RANK_PRIMARY);
    }

    @Test
    public void reconnectedAndStoppedRecordsAreLeftAlone() {
        Application live = inherited("Sweep", "HealthyApp", 1);
        live.setConnection(new FakeConnection());
        Application stopped = inherited("Sweep", "HealthyApp", 2);
        stopped.setRunLevel(RunLevel.TERMINATED);

        server.retireUnclaimedRecords();

        Assert.assertEquals(live.getRunLevel(), RunLevel.RUNNING);
        Assert.assertEquals(live.getRank(), RANK_PRIMARY);
        Assert.assertEquals(stopped.getRunLevel(), RunLevel.TERMINATED);
    }

    @Test
    public void aMirrorDoesNotSweep() {
        Application unclaimed = inherited("Sweep", "MirrorApp", 1);
        RegistryServer.setRank(2);
        try {
            server.retireUnclaimedRecords();
        } finally {
            RegistryServer.setRank(RANK_PRIMARY);
        }
        Assert.assertEquals(unclaimed.getRunLevel(), RunLevel.RUNNING);
    }

    // As a mirror holds it after syncing: running, with no connection
    private Application inherited(String group, String name, int rank) {
        Application application = applications.createApplication(group, name, rank);
        application.setRunLevel(RunLevel.RUNNING);
        return application;
    }
}
