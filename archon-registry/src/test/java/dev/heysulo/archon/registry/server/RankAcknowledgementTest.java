package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.dictionary.sdk.RegistryProtocol.RANK_UNKNOWN;

/**
 * An instance is starting until it acknowledges its rank, which it does once ready for it. Only acknowledged
 * instances can be promoted.
 */
public class RankAcknowledgementTest {
    private static final String GROUP = "ack";
    private static final AtomicInteger APP_COUNTER = new AtomicInteger();

    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    @Test
    public void staysStartingUntilItAcknowledges() {
        String app = uniqueAppName();
        FakeConnection connection = new FakeConnection();
        Registration registration = register(app, connection);
        Assert.assertEquals(record(app, registration.rank()).getRunLevel(), RunLevel.STARTING);

        acknowledge(app, registration.rank(), registration.rank(), connection);
        Assert.assertEquals(record(app, registration.rank()).getRunLevel(), RunLevel.RUNNING);
    }

    // The instance was promoted while finishing its last role; its late acknowledgement must not stop it
    @Test
    public void ignoresAnAcknowledgementOfAnOlderRank() {
        String app = uniqueAppName();
        register(app, new FakeConnection());
        FakeConnection mirror = new FakeConnection();
        Registration registration = register(app, mirror);

        acknowledge(app, registration.rank(), registration.rank() + 5, mirror);

        Assert.assertTrue(mirror.isConnected());
        Assert.assertNull(mirror.termination());
        Assert.assertEquals(record(app, registration.rank()).getRunLevel(), RunLevel.STARTING);
    }

    // The leader went while its only mirror was still warming up, so no one could be promoted then
    @Test
    public void electsALeaderWhenTheFirstMirrorBecomesReady() {
        String app = uniqueAppName();
        FakeConnection leader = new FakeConnection();
        Registration first = register(app, leader);
        acknowledge(app, first.rank(), first.rank(), leader);
        FakeConnection mirror = new FakeConnection();
        Registration second = register(app, mirror);

        leader.drop();
        Assert.assertNull(mirror.last(ApplicationRankUpdate.class), "not ready, so not promoted");

        acknowledge(app, second.rank(), second.rank(), mirror);

        Assert.assertEquals(mirror.last(ApplicationRankUpdate.class).getRank(), RANK_PRIMARY);
        Application promoted = ApplicationManager.getInstance().findAllApplications(GROUP, app).stream()
                .filter(record -> record.getConnection() == mirror).findFirst().orElseThrow();
        Assert.assertEquals(promoted.getRank(), RANK_PRIMARY);
        Assert.assertEquals(promoted.getRunLevel(), RunLevel.CHANGING, "until it acknowledges being leader");
    }

    // Just after a failover the leader may not have reconnected yet: a mirror reconnecting first must not take its
    // rank. The sweep at the end of the grace period decides.
    @Test
    public void electsNoOneWhileInstancesMayStillBeReconnecting() {
        String app = uniqueAppName();
        FakeConnection leader = new FakeConnection();
        Registration first = register(app, leader);
        acknowledge(app, first.rank(), first.rank(), leader);
        FakeConnection mirror = new FakeConnection();
        Registration second = register(app, mirror);
        leader.drop();
        RegistryServer.beganActingAsPrimary(System.nanoTime());
        try {
            acknowledge(app, second.rank(), second.rank(), mirror);
            Assert.assertNull(mirror.last(ApplicationRankUpdate.class), "not promoted during the grace period");
        } finally {
            RegistryServer.beganActingAsPrimary(System.nanoTime() - Duration.ofMinutes(5).toNanos());
        }
    }

    @Test
    public void aMirrorBecomingReadyLeavesALiveLeaderAlone() {
        String app = uniqueAppName();
        register(app, new FakeConnection());
        FakeConnection mirror = new FakeConnection();
        Registration second = register(app, mirror);

        acknowledge(app, second.rank(), second.rank(), mirror);

        Assert.assertNull(mirror.last(ApplicationRankUpdate.class));
        Assert.assertEquals(record(app, second.rank()).getRunLevel(), RunLevel.RUNNING);
    }

    // A reconnect must not pass an instance off as ready when it had not acknowledged its rank
    @Test
    public void aReconnectKeepsAnUnreadyInstanceStarting() {
        String app = uniqueAppName();
        FakeConnection first = new FakeConnection();
        Registration registration = register(app, first);
        first.drop();

        FakeConnection again = new FakeConnection();
        server.onMessage(null, again, registrationMessage(app, registration.rank(), registration.token(), false));
        Assert.assertEquals(record(app, registration.rank()).getRunLevel(), RunLevel.STARTING);

        again.drop();
        FakeConnection ready = new FakeConnection();
        server.onMessage(null, ready, registrationMessage(app, registration.rank(), registration.token(), true));
        Assert.assertEquals(record(app, registration.rank()).getRunLevel(), RunLevel.RUNNING);
    }

    // Older SDKs say nothing, and acknowledged every rank at once
    @Test
    public void aReconnectFromAnOlderSdkIsReady() {
        String app = uniqueAppName();
        FakeConnection first = new FakeConnection();
        Registration registration = register(app, first);
        first.drop();

        server.onMessage(null, new FakeConnection(),
                registrationMessage(app, registration.rank(), registration.token(), null));
        Assert.assertEquals(record(app, registration.rank()).getRunLevel(), RunLevel.RUNNING);
    }

    private static String uniqueAppName() {
        return "app" + APP_COUNTER.incrementAndGet();
    }

    private Registration register(String app, FakeConnection connection) {
        server.onMessage(null, connection, registrationMessage(app, RANK_UNKNOWN, null, null));
        ApplicationRegistrationResponseMessage response = connection.last(ApplicationRegistrationResponseMessage.class);
        Assert.assertNotNull(response, "no registration response");
        return new Registration(response.getRank(), response.getAuthenticationToken());
    }

    private static void acknowledge(String app, int recordRank, int acknowledged, FakeConnection connection) {
        record(app, recordRank).onMessage(connection, new ApplicationRankUpdate(acknowledged));
    }

    private static ApplicationRegistrationMessage registrationMessage(String app, int rank, String token,
            Boolean ready) {
        ApplicationRegistrationMessage message = new ApplicationRegistrationMessage(app, GROUP, "0.0.0.0");
        message.setRank(rank);
        message.setAuthenticationToken(token);
        message.setReady(ready);
        return message;
    }

    private static Application record(String app, int rank) {
        return ApplicationManager.getInstance().findApplication(GROUP, app, rank);
    }

    private record Registration(int rank, String token) {
    }
}
