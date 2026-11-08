package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHello;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHelloResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRows;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.archon.sdk.services.TestServiceClients;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class ConfigReplicaTest {
    private ConfigReplica replica;
    private ConfigReplicaClient client;
    private ScheduledExecutorService scheduler;

    @BeforeMethod
    public void setUp() {
        replica = new ConfigReplica();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        client = new ConfigReplicaClient("Archon", "Portal", () -> 1, () -> "token",
                (serviceId, callback) -> TestServiceClients.unconnected(serviceId, callback), replica, scheduler);
    }

    @AfterMethod
    public void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    public void keepsEveryConfigurationInOrder() {
        receive(new ConfigRows(List.of(row(3, ConfigLevel.APPLICATION, "Shop", "Cart", "b", 3),
                row(1, ConfigLevel.GLOBAL, "", "", "z", 1), row(2, ConfigLevel.GROUP, "Shop", "", "a", 2)), 3, true,
                true));

        Assert.assertTrue(client.isSynced());
        Assert.assertEquals(replica.revision(), 3);
        Assert.assertEquals(replica.entries().stream().map(ConfigEntry::name).toList(), List.of("z", "a", "b"));
    }

    @Test
    public void appliesABatchOnlyOnceItsLastPieceArrives() {
        receive(new ConfigRows(List.of(row(1, ConfigLevel.GLOBAL, "", "", "a", 1)), 2, true, false));
        Assert.assertEquals(replica.size(), 0);
        Assert.assertFalse(client.isSynced());
        receive(new ConfigRows(List.of(row(2, ConfigLevel.GLOBAL, "", "", "b", 2)), 2, false, true));
        Assert.assertEquals(replica.size(), 2);
        Assert.assertEquals(replica.revision(), 2);
    }

    @Test
    public void appliesChangesAndDeletionsAndIgnoresOlderRows() {
        receive(new ConfigRows(List.of(row(1, ConfigLevel.GLOBAL, "", "", "a", 1),
                row(2, ConfigLevel.GLOBAL, "", "", "b", 2)), 2, true, true));

        receive(new ConfigRows(List.of(new ConfigRow(entry(1, ConfigLevel.GLOBAL, "", "", "a", "new"), false, 3),
                new ConfigRow(entry(2, ConfigLevel.GLOBAL, "", "", "b", "x"), true, 4)), 4, false, true));
        Assert.assertEquals(replica.get(1).orElseThrow().value(), "new");
        Assert.assertTrue(replica.get(2).isEmpty(), "deleted");

        // Arriving late, after a newer change to the same row, it is ignored
        receive(new ConfigRows(List.of(new ConfigRow(entry(1, ConfigLevel.GLOBAL, "", "", "a", "old"), false, 2)),
                4, false, true));
        Assert.assertEquals(replica.get(1).orElseThrow().value(), "new");
        Assert.assertEquals(replica.revision(), 4);
    }

    @Test
    public void replacesEverythingOnAReset() {
        receive(new ConfigRows(List.of(row(1, ConfigLevel.GLOBAL, "", "", "a", 1)), 1, true, true));
        receive(new ConfigRows(List.of(row(7, ConfigLevel.GLOBAL, "", "", "c", 7)), 7, true, true));
        Assert.assertEquals(replica.entries().stream().map(ConfigEntry::name).toList(), List.of("c"));
    }

    // After a reconnect it asks only for what it missed
    @Test
    public void asksForWhatItMissedOnReconnect() {
        receive(new ConfigRows(List.of(row(5, ConfigLevel.GLOBAL, "", "", "a", 5)), 9, true, true));
        client.onConnect(TestServiceClients.unconnected("archon-config", client),
                new ServiceEndpoint("10.0.0.1", 9101, 1, true));
        // The test connection cannot send, so check the hello it would have sent
        ConfigHello hello = client.hello(1, "token");
        Assert.assertTrue(hello.isEverything());
        Assert.assertEquals(hello.getSinceRevision(), 9);
    }

    private void receive(Message message) {
        client.onMessage(null, ConfigHelloResponse.accepted());
        client.onMessage(null, message);
    }

    private static ConfigRow row(long id, ConfigLevel level, String group, String application, String name,
            long revision) {
        return new ConfigRow(entry(id, level, group, application, name, "v"), false, revision);
    }

    private static ConfigEntry entry(long id, ConfigLevel level, String group, String application, String name,
            String value) {
        return new ConfigEntry(id, level, group, application, name, ConfigType.STRING, value, false, "", null, null);
    }
}
