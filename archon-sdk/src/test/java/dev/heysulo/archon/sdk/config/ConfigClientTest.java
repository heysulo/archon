package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHelloResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigSnapshot;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.archon.sdk.services.ServiceClient;
import dev.heysulo.archon.sdk.services.ServiceException;
import dev.heysulo.archon.sdk.services.TestServiceClients;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConfigClientTest {
    private final List<String> heard = new CopyOnWriteArrayList<>();
    private final AtomicInteger connects = new AtomicInteger();
    private RecordingScheduler scheduler;
    private volatile RuntimeException connectFailure;
    private ConfigClient client;

    @BeforeMethod
    public void setUp() {
        heard.clear();
        connects.set(0);
        connectFailure = null;
        scheduler = new RecordingScheduler();
        client = new ConfigClient("Shop", "Cart", () -> 1, () -> "token", (serviceId, callback) -> {
            connects.incrementAndGet();
            if (connectFailure != null) {
                throw connectFailure;
            }
            return TestServiceClients.unconnected(serviceId, callback);
        }, (name, oldValue, newValue) -> heard.add(name + ":" + raw(oldValue) + "->" + raw(newValue)), scheduler);
    }

    @AfterMethod
    public void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    public void keepsTheFirstConfigurationsInMemory() {
        snapshot(List.of(entry("limit", ConfigType.INTEGER, "100"),
                entry("currency", ConfigType.STRING, "EUR")));

        Assert.assertTrue(client.awaitFirstSnapshot(0));
        Assert.assertEquals(client.get("limit").orElseThrow().asLong(), 100L);
        Assert.assertEquals(client.all().size(), 2);
        Assert.assertTrue(heard.contains("limit:null->100"), heard.toString());
        Assert.assertTrue(heard.contains("currency:null->EUR"), heard.toString());
    }

    @Test
    public void appliesPushedChangesAndReportsEach() {
        snapshot(List.of(entry("limit", ConfigType.INTEGER, "100"),
                entry("currency", ConfigType.STRING, "EUR")));
        heard.clear();

        client.onMessage(null, new ConfigChanged(List.of(entry("limit", ConfigType.INTEGER, "150")), List.of()));
        client.onMessage(null, new ConfigChanged(List.of(), List.of("currency")));

        Assert.assertEquals(heard, List.of("limit:100->150", "currency:EUR->null"));
        Assert.assertEquals(client.get("limit").orElseThrow().asInt(), 150);
        Assert.assertTrue(client.get("currency").isEmpty());
    }

    // After a reconnect everything arrives again; only what really changed meanwhile is reported
    @Test
    public void reportsOnlyRealChangesAfterAReconnect() {
        snapshot(List.of(entry("limit", ConfigType.INTEGER, "100"),
                entry("currency", ConfigType.STRING, "EUR")));
        heard.clear();

        snapshot(List.of(entry("limit", ConfigType.INTEGER, "100"),
                entry("flag", ConfigType.BOOLEAN, "true")));

        Assert.assertEquals(heard.size(), 2, heard.toString());
        Assert.assertTrue(heard.containsAll(List.of("currency:EUR->null", "flag:null->true")), heard.toString());
    }

    @Test
    public void refusesRequestsUntilAccepted() {
        ConfigurationException refused = Assert.expectThrows(ConfigurationException.class,
                () -> client.set("limit", "1"));
        Assert.assertTrue(refused.isUnavailable());
        Assert.assertFalse(client.awaitFirstSnapshot(0));
    }

    @Test
    public void startsOverWhenRefused() {
        client.start();
        Assert.assertEquals(connects.get(), 1);
        ServiceClient connection = TestServiceClients.unconnected(ConfigClient.SERVICE, client);
        client.onMessage(connection, ConfigHelloResponse.refused("Cannot confirm who you are right now"));

        Assert.assertTrue(connection.isClosed());
        Assert.assertEquals(scheduler.delays, List.of(2_000L));
    }

    @Test
    public void checksRarelyForAServiceThatIsNotDefined() {
        connectFailure = new ServiceException("Unknown service archon-config");
        client.start();
        Assert.assertEquals(scheduler.delays, List.of(30_000L));

        scheduler.delays.clear();
        connectFailure = new ServiceException("No instance of archon-config is running");
        client.start();
        Assert.assertEquals(scheduler.delays, List.of(2_000L));
    }

    // Uses the pieces only once the last one arrives
    @Test
    public void waitsForEveryPieceOfTheSnapshot() {
        client.onMessage(null, ConfigHelloResponse.accepted());
        client.onMessage(null, new ConfigSnapshot(List.of(entry("limit", ConfigType.INTEGER, "100")), false));
        Assert.assertTrue(client.all().isEmpty());
        Assert.assertFalse(client.awaitFirstSnapshot(0));

        client.onMessage(null, new ConfigSnapshot(List.of(entry("currency", ConfigType.STRING, "EUR")), true));
        Assert.assertEquals(client.all().size(), 2);
        Assert.assertTrue(client.awaitFirstSnapshot(0));
    }

    // A snapshot cut short by a disconnect is dropped, not mixed into the next one
    @Test
    public void dropsAHalfReceivedSnapshotOnReconnect() {
        client.onMessage(null, ConfigHelloResponse.accepted());
        client.onMessage(null, new ConfigSnapshot(List.of(entry("stale", ConfigType.STRING, "x")), false));
        client.onConnect(TestServiceClients.unconnected(ConfigClient.SERVICE, client),
                new ServiceEndpoint("10.0.0.1", 9101, 1, true));
        snapshot(List.of(entry("limit", ConfigType.INTEGER, "100")));
        Assert.assertEquals(client.all().keySet(), java.util.Set.of("limit"));
    }

    private void snapshot(List<ConfigEntry> entries) {
        client.onMessage(null, ConfigHelloResponse.accepted());
        client.onMessage(null, new ConfigSnapshot(entries, true));
    }

    private static ConfigEntry entry(String name, ConfigType type, String value) {
        return new ConfigEntry(1, ConfigLevel.GLOBAL, "", "", name, type, value, false, "", null, null);
    }

    private static String raw(ConfigValue value) {
        return value == null ? "null" : value.raw();
    }

    /** Runs immediate tasks at once and records the delay of every later one without running it. */
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        private final List<Long> delays = new CopyOnWriteArrayList<>();

        RecordingScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delays.add(unit.toMillis(delay));
            return null;
        }
    }
}
