package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import dev.heysulo.archon.registry.constants.ApplicationTermination;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;

public class ReRegistrationTest {

    private static final String GROUP = "test";
    private static final AtomicInteger APP_COUNTER = new AtomicInteger();

    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    @Test
    public void reconnectingInstanceReclaimsItsRankSoItIsNotHandedOut() {
        String app = uniqueAppName();
        FakeConnection first = new FakeConnection();
        Registration registration = register(app, first);
        first.drop();

        FakeConnection reconnect = new FakeConnection();
        reRegister(app, registration.rank(), registration.token(), reconnect);
        Registration newcomer = register(app, new FakeConnection());

        Assert.assertNull(reconnect.termination());
        Assert.assertNotEquals(newcomer.rank(), registration.rank());
        Assert.assertSame(record(app, registration.rank()).getConnection(), reconnect);
    }

    @Test
    public void reconnectingInstanceIsTerminatedWhenAnotherInstanceHoldsItsRank() {
        String app = uniqueAppName();
        FakeConnection first = new FakeConnection();
        Registration registration = register(app, first);
        first.drop();
        FakeConnection replacement = new FakeConnection();
        Registration takeover = register(app, replacement);
        Assert.assertEquals(takeover.rank(), registration.rank());

        FakeConnection reconnect = new FakeConnection();
        reRegister(app, registration.rank(), registration.token(), reconnect);

        Assert.assertEquals(reconnect.termination(), ApplicationTermination.REASON_RANK_HELD_BY_ANOTHER_INSTANCE);
        Assert.assertSame(record(app, registration.rank()).getConnection(), replacement);
    }

    @Test
    public void reconnectingWithoutTokenIsTerminatedWhileTheRankIsHeld() {
        String app = uniqueAppName();
        Registration registration = register(app, new FakeConnection());

        FakeConnection reconnect = new FakeConnection();
        reRegister(app, registration.rank(), null, reconnect);

        Assert.assertEquals(reconnect.termination(), ApplicationTermination.REASON_RANK_HELD_BY_ANOTHER_INSTANCE);
    }

    @Test
    public void sameInstanceMayReconnectBeforeItsOldConnectionIsNoticedAsDead() {
        String app = uniqueAppName();
        Registration registration = register(app, new FakeConnection());

        FakeConnection reconnect = new FakeConnection();
        reRegister(app, registration.rank(), registration.token(), reconnect);

        Assert.assertNull(reconnect.termination());
        Assert.assertSame(record(app, registration.rank()).getConnection(), reconnect);
    }

    @Test
    public void reconnectingWithARankNeverIssuedIsTerminated() {
        FakeConnection reconnect = new FakeConnection();
        reRegister(uniqueAppName(), 7, "token", reconnect);

        Assert.assertEquals(reconnect.termination(),
                ApplicationTermination.REASON_RANK_UNAVAILABLE_FOR_RE_REGISTRATION);
    }

    private static String uniqueAppName() {
        return "app" + APP_COUNTER.incrementAndGet();
    }

    private Registration register(String app, FakeConnection connection) {
        server.onMessage(null, connection, registrationMessage(app, RANK_UNKNOWN, null));
        ApplicationRegistrationResponseMessage response = connection.last(ApplicationRegistrationResponseMessage.class);
        Assert.assertNotNull(response, "no registration response");
        return new Registration(response.getRank(), response.getAuthenticationToken());
    }

    private void reRegister(String app, int rank, String token, FakeConnection connection) {
        server.onMessage(null, connection, registrationMessage(app, rank, token));
    }

    private static ApplicationRegistrationMessage registrationMessage(String app, int rank, String token) {
        ApplicationRegistrationMessage message = new ApplicationRegistrationMessage(app, GROUP, "0.0.0.0");
        message.setRank(rank);
        message.setAuthenticationToken(token);
        return message;
    }

    private static Application record(String app, int rank) {
        return ApplicationManager.getInstance().findApplication(GROUP, app, rank);
    }

    private record Registration(int rank, String token) {
    }
}
