package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHello;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHelloResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigResponse;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.ConnectionCallback;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Clock;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static dev.heysulo.archon.config.TestStore.entry;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigLevel.GLOBAL;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigLevel.GROUP;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigType.INTEGER;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigType.STRING;

public class ConfigServerTest {
    private static final String TOKEN = "token";

    private TestStore database;
    private ConfigService service;
    private ConfigServer server;
    private ExecutorService workers;
    private volatile Verifier.Result verdict;

    @BeforeMethod
    public void setUp() throws Exception {
        database = new TestStore();
        service = new ConfigService(database.store(), Clock.systemUTC());
        workers = Executors.newFixedThreadPool(2);
        verdict = Verifier.Result.VALID;
        server = new ConfigServer(service, (group, name, rank, token) ->
                TOKEN.equals(token) ? verdict : Verifier.Result.INVALID, workers);
        service.create(entry(GLOBAL, "", "", "limit", INTEGER, "100", false), "admin");
    }

    @AfterMethod
    public void tearDown() {
        workers.shutdownNow();
        database.close();
    }

    @Test
    public void sendsWhatTheApplicationSeesOnceVerified() throws Exception {
        FakeConnection shop = connect("Shop", "Cart", TOKEN);
        ConfigHelloResponse hello = shop.next(ConfigHelloResponse.class);
        Assert.assertTrue(hello.isAccepted());
        Assert.assertEquals(hello.getEntries().get(0).name(), "limit");

        service.create(entry(GROUP, "Shop", "", "limit", INTEGER, "50", false), "admin");
        ConfigChanged changed = shop.next(ConfigChanged.class);
        Assert.assertEquals(changed.getChanged().get(0).value(), "50");
    }

    @Test
    public void refusesCallersTheRegistryDoesNotKnow() throws Exception {
        FakeConnection stranger = connect("Shop", "Cart", "guess");
        Assert.assertFalse(stranger.next(ConfigHelloResponse.class).isAccepted());
        Assert.assertEquals(service.subscriberCount(), 0);
    }

    @Test
    public void asksCallersToRetryWhileNoRegistryAnswers() throws Exception {
        verdict = Verifier.Result.UNAVAILABLE;
        FakeConnection shop = connect("Shop", "Cart", TOKEN);
        Assert.assertFalse(shop.next(ConfigHelloResponse.class).isAccepted());

        verdict = Verifier.Result.VALID;
        server.onMessage(null, shop, new ConfigHello("Shop", "Cart", 1, TOKEN));
        Assert.assertTrue(shop.next(ConfigHelloResponse.class).isAccepted(), "a later hello is heard");
    }

    @Test
    public void refusesRequestsBeforeTheHello() throws Exception {
        FakeConnection anyone = new FakeConnection();
        server.onConnect(null, anyone);
        server.onMessage(null, anyone, new ConfigRequest(1, ConfigRequest.Operation.LIST_ALL, null, null));
        Assert.assertFalse(anyone.next(ConfigResponse.class).isOk());
    }

    @Test
    public void onlyThePortalManagesConfigurations() throws Exception {
        FakeConnection shop = connect("Shop", "Cart", TOKEN);
        shop.next(ConfigHelloResponse.class);
        server.onMessage(null, shop, new ConfigRequest(1, ConfigRequest.Operation.LIST_ALL, null, null));
        Assert.assertFalse(shop.next(ConfigResponse.class).isOk());

        FakeConnection portal = connect(ConfigServer.PORTAL_GROUP, ConfigServer.PORTAL_NAME, TOKEN);
        portal.next(ConfigHelloResponse.class);
        server.onMessage(null, portal, new ConfigRequest(2, ConfigRequest.Operation.CREATE,
                entry(GROUP, "Shop", "", "currency", STRING,
                        "EUR", true), "dana"));
        ConfigResponse created = portal.next(ConfigResponse.class);
        Assert.assertTrue(created.isOk(), created.getError());
        Assert.assertEquals(created.getEntries().get(0).updatedBy(), "dana");
    }

    @Test
    public void applicationsSetTheirOwnValues() throws Exception {
        FakeConnection shop = connect("Shop", "Cart", TOKEN);
        shop.next(ConfigHelloResponse.class);
        server.onMessage(null, shop, new ConfigRequest(1, ConfigRequest.Operation.SET,
                new ConfigEntry(0, null, null, null, "limit", null, "120", false, null, null, null), null));
        // The change is pushed to the setter too, like to every caller that sees it, before the answer
        Assert.assertEquals(shop.next(ConfigChanged.class).getChanged().get(0).value(), "120");
        ConfigResponse set = shop.next(ConfigResponse.class);
        Assert.assertTrue(set.isOk(), set.getError());
        Assert.assertEquals(set.getEntries().get(0).updatedBy(), "Shop/Cart");
    }

    @Test
    public void stopsPushingOnceDisconnected() throws Exception {
        FakeConnection shop = connect("Shop", "Cart", TOKEN);
        shop.next(ConfigHelloResponse.class);
        server.onDisconnect(null, shop);
        Assert.assertEquals(service.subscriberCount(), 0);
        Assert.assertEquals(server.sessionCount(), 0);
    }

    private FakeConnection connect(String group, String name, String token) {
        FakeConnection connection = new FakeConnection();
        server.onConnect(null, connection);
        server.onMessage(null, connection, new ConfigHello(group, name, 1, token));
        return connection;
    }

    /** Collects what the server sends. */
    private static final class FakeConnection implements Connection {
        private final BlockingQueue<Message> sent = new LinkedBlockingQueue<>();

        <T extends Message> T next(Class<T> type) throws InterruptedException {
            Message message = sent.poll(5, TimeUnit.SECONDS);
            Assert.assertNotNull(message, "nothing was sent");
            Assert.assertTrue(type.isInstance(message), "sent " + message.getClass().getSimpleName());
            return type.cast(message);
        }

        @Override
        public void send(Message message) {
            sent.add(message);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public OptionalLong getLatencyMillis() {
            return OptionalLong.empty();
        }

        @Override
        public void setCallback(ConnectionCallback callback) {
        }

        @Override
        public String getRemoteAddress() {
            return "10.0.0.9";
        }

        @Override
        public int getRemotePort() {
            return 40000;
        }
    }
}
