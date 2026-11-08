package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRows;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigSnapshot;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.heysulo.archon.config.TestStore.entry;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigLevel.APPLICATION;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigLevel.GLOBAL;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigLevel.GROUP;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigType.BOOLEAN;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigType.INTEGER;
import static dev.heysulo.archon.dictionary.sdk.config.ConfigType.STRING;

public class ConfigServiceTest {
    private TestStore database;
    private ConfigService service;

    @BeforeMethod
    public void setUp() throws Exception {
        database = new TestStore();
        service = new ConfigService(database.store(), Clock.systemUTC());
    }

    @AfterMethod
    public void tearDown() {
        database.close();
    }

    @Test
    public void registersItsOwnServiceOnce() throws Exception {
        Assert.assertEquals(database.serviceRows(), 1);
    }

    @Test
    public void theMostSpecificLevelWins() {
        service.create(entry(GLOBAL, "", "", "timeout", INTEGER, "30", false), "admin");
        service.create(entry(GROUP, "Shop", "", "timeout", INTEGER, "20", false), "admin");
        service.create(entry(APPLICATION, "Shop", "Cart", "timeout", INTEGER, "10", false), "admin");

        Assert.assertEquals(seen("Shop", "Cart").get("timeout").value(), "10");
        Assert.assertEquals(seen("Shop", "Checkout").get("timeout").value(), "20");
        Assert.assertEquals(seen("Bank", "Ledger").get("timeout").value(), "30");
    }

    @Test
    public void applicationsSeeOnlyTheirOwnGroupAndApplication() {
        service.create(entry(GROUP, "Shop", "", "currency", STRING, "EUR", false), "admin");
        service.create(entry(APPLICATION, "Shop", "Cart", "basket.size", INTEGER, "50", false), "admin");

        Assert.assertEquals(seen("Shop", "Cart").keySet(), Set.of("currency", "basket.size"));
        Assert.assertEquals(seen("Shop", "Checkout").keySet(), Set.of("currency"));
        Assert.assertTrue(seen("Bank", "Cart").isEmpty(), "a group's configs stay in that group");
    }

    @Test
    public void pushesOnlyWhatChangedForEachCaller() {
        service.create(entry(GLOBAL, "", "", "timeout", INTEGER, "30", false), "admin");
        ConfigEntry cart = service.create(entry(APPLICATION, "Shop", "Cart", "timeout", INTEGER, "10", false),
                "admin");
        List<ConfigChanged> toCart = subscribe("Shop", "Cart");
        List<ConfigChanged> toLedger = subscribe("Bank", "Ledger");

        service.update(cart.withValue("5"), "admin");
        Assert.assertEquals(toCart.size(), 1);
        Assert.assertEquals(toCart.get(0).getChanged().get(0).value(), "5");
        Assert.assertTrue(toLedger.isEmpty(), "the global value did not change for Ledger");

        // Deleting the application's own falls back to the global one, which is a change, not a removal
        service.delete(cart.id(), "admin");
        Assert.assertEquals(toCart.get(1).getChanged().get(0).value(), "30");
        Assert.assertTrue(toCart.get(1).getRemoved().isEmpty());
    }

    @Test
    public void tellsCallersWhenANameDisappears() {
        ConfigEntry global = service.create(entry(GLOBAL, "", "", "feature", BOOLEAN, "true", false), "admin");
        List<ConfigChanged> pushed = subscribe("Shop", "Cart");
        service.delete(global.id(), "admin");
        Assert.assertEquals(pushed.get(0).getRemoved(), List.of("feature"));
    }

    @Test
    public void applicationsSetTheValueTheySee() {
        service.create(entry(GLOBAL, "", "", "timeout", INTEGER, "30", false), "admin");
        ConfigEntry group = service.create(entry(GROUP, "Shop", "", "timeout", INTEGER, "20", false), "admin");

        ConfigEntry set = service.setValue("Shop", "Cart", "timeout", " 25 ");
        Assert.assertEquals(set.id(), group.id(), "the most specific one it sees is changed");
        Assert.assertEquals(set.value(), "25");
        Assert.assertEquals(set.updatedBy(), "Shop/Cart");
        Assert.assertEquals(seen("Bank", "Ledger").get("timeout").value(), "30");
    }

    @Test
    public void refusesApplicationChangesToReadOnlyConfigurations() {
        service.create(entry(GLOBAL, "", "", "limit", INTEGER, "100", true), "admin");
        ConfigException refused = Assert.expectThrows(ConfigException.class,
                () -> service.setValue("Shop", "Cart", "limit", "200"));
        Assert.assertTrue(refused.getMessage().contains("read only"), refused.getMessage());
        Assert.assertEquals(seen("Shop", "Cart").get("limit").value(), "100");
    }

    @Test
    public void refusesValuesOfTheWrongType() {
        service.create(entry(GLOBAL, "", "", "limit", INTEGER, "100", false), "admin");
        Assert.expectThrows(ConfigException.class, () -> service.setValue("Shop", "Cart", "limit", "lots"));
        Assert.expectThrows(ConfigException.class,
                () -> service.create(entry(GLOBAL, "", "", "flag", BOOLEAN, "yes", false), "admin"));
        Assert.expectThrows(ConfigException.class,
                () -> service.setValue("Shop", "Cart", "missing", "1"));
    }

    @Test
    public void refusesBadIdentifiersAndDuplicates() {
        Assert.expectThrows(ConfigException.class,
                () -> service.create(entry(GLOBAL, "", "", "has space", STRING, "x", false), "admin"));
        Assert.expectThrows(ConfigException.class,
                () -> service.create(entry(GROUP, "", "", "name", STRING, "x", false), "admin"));
        service.create(entry(GLOBAL, "", "", "name", STRING, "x", false), "admin");
        Assert.expectThrows(ConfigException.class,
                () -> service.create(entry(GLOBAL, "", "", "name", STRING, "y", false), "admin"));
    }

    @Test
    public void ignoresPartsALevelDoesNotUse() {
        ConfigEntry created = service.create(entry(GLOBAL, "Shop", "Cart", "name", STRING, "x", false), "admin");
        Assert.assertEquals(created.group(), "");
        Assert.assertEquals(created.application(), "");
    }

    @Test
    public void recreatesADeletedConfiguration() {
        ConfigEntry first = service.create(entry(GLOBAL, "", "", "name", STRING, "x", false), "admin");
        service.delete(first.id(), "admin");
        ConfigEntry again = service.create(entry(GLOBAL, "", "", "name", STRING, "y", false), "admin");
        Assert.assertEquals(seen("Shop", "Cart").get("name").value(), "y");
        Assert.assertEquals(service.listAll().size(), 1);
        Assert.assertNotNull(again);
    }

    @Test
    public void addsTheRevisionIndex() throws Exception {
        Assert.assertTrue(database.hasIndex(ConfigDatabase.REVISION_INDEX));
    }

    // A more specific configuration hides changes to less specific ones with the same name
    @Test
    public void pushesNothingWhenAMoreSpecificConfigurationHidesTheChange() {
        ConfigEntry global = service.create(entry(GLOBAL, "", "", "timeout", INTEGER, "30", false), "admin");
        service.create(entry(APPLICATION, "Shop", "Cart", "timeout", INTEGER, "10", false), "admin");
        List<ConfigChanged> toCart = subscribe("Shop", "Cart");
        List<ConfigChanged> toCheckout = subscribe("Shop", "Checkout");

        service.update(global.withValue("40"), "admin");

        Assert.assertTrue(toCart.isEmpty(), toCart.toString());
        Assert.assertEquals(toCheckout.get(0).getChanged().get(0).value(), "40");
    }

    @Test
    public void sendsTheSnapshotInPieces() throws Exception {
        ConfigService small = new ConfigService(database.store(), Clock.systemUTC(), 2_000);
        for (int i = 0; i < 20; i++) {
            small.create(entry(GLOBAL, "", "", "name" + i, STRING, "x".repeat(200), false), "admin");
        }
        List<Message> pushed = new CopyOnWriteArrayList<>();
        small.subscribe(new ConfigService.Subscriber("Shop", "Cart", pushed::add));

        Assert.assertTrue(pushed.size() > 1, "split into " + pushed.size());
        Assert.assertEquals(pushed.stream().filter(m -> ((ConfigSnapshot) m).isLast()).count(), 1);
        Assert.assertTrue(((ConfigSnapshot) pushed.get(pushed.size() - 1)).isLast());
        Assert.assertEquals(pushed.stream().mapToInt(m -> ((ConfigSnapshot) m).getEntries().size()).sum(), 20);
    }

    // ---------------- replicas ----------------

    @Test
    public void sendsANewReplicaEverythingThenEachChange() {
        ConfigEntry kept = service.create(entry(GLOBAL, "", "", "kept", STRING, "x", false), "admin");
        ConfigEntry gone = service.create(entry(GLOBAL, "", "", "gone", STRING, "x", false), "admin");
        service.delete(gone.id(), "admin");

        List<Message> pushed = replicate(0);
        ConfigRows all = (ConfigRows) pushed.get(0);
        Assert.assertTrue(all.isReset() && all.isLast());
        Assert.assertEquals(all.getRows().stream().map(row -> row.entry().name()).toList(), List.of("kept"));
        Assert.assertEquals(all.getRevision(), service.revision());

        service.update(kept.withValue("y"), "admin");
        ConfigRows change = (ConfigRows) pushed.get(1);
        Assert.assertFalse(change.isReset());
        Assert.assertEquals(change.getRows().get(0).entry().value(), "y");
        Assert.assertEquals(change.getRevision(), service.revision());
    }

    @Test
    public void sendsAReturningReplicaOnlyWhatItMissedDeletionsIncluded() {
        ConfigEntry first = service.create(entry(GLOBAL, "", "", "first", STRING, "x", false), "admin");
        long seen = service.revision();
        service.create(entry(GLOBAL, "", "", "second", STRING, "x", false), "admin");
        service.delete(first.id(), "admin");

        ConfigRows missed = (ConfigRows) replicate(seen).get(0);
        Assert.assertFalse(missed.isReset());
        Assert.assertEquals(missed.getRows().stream().map(row -> row.entry().name() + ":" + row.deleted()).toList(),
                List.of("second:false", "first:true"));
    }

    // The database was replaced: the replica's revision means nothing here, so it starts over
    @Test
    public void startsAReplicaOverWhenItIsAhead() {
        service.create(entry(GLOBAL, "", "", "only", STRING, "x", false), "admin");
        ConfigRows all = (ConfigRows) replicate(service.revision() + 100).get(0);
        Assert.assertTrue(all.isReset());
        Assert.assertEquals(all.getRows().size(), 1);
    }

    // A mirror applies the primary's rows through the same service, ready to take over
    @Test
    public void mirrorsApplyThePrimarysRows() {
        ConfigService mirror = new ConfigService(database.store(), Clock.systemUTC());
        service.subscribeReplica(new ConfigService.Replica(message -> {
            ConfigRows rows = (ConfigRows) message;
            if (rows.isReset()) {
                mirror.replace(rows.getRows(), rows.getRevision());
            } else {
                mirror.apply(rows.getRows(), rows.getRevision());
            }
        }), mirror.revision());

        ConfigEntry created = service.create(entry(GROUP, "Shop", "", "currency", STRING, "EUR", false), "admin");
        service.update(created.withValue("USD"), "admin");

        Assert.assertEquals(mirror.revision(), service.revision());
        Assert.assertEquals(mirror.visibleTo("Shop", "Cart").get(0).value(), "USD");
    }

    // Two replicas share the database. A change made through one reaches the other's callers on its next refresh.
    @Test
    public void picksUpChangesMadeByAnotherServer() {
        ConfigService other = new ConfigService(database.store(), Clock.systemUTC());
        List<ConfigChanged> pushed = subscribe("Shop", "Cart");
        other.create(entry(GROUP, "Shop", "", "currency", STRING, "EUR", false), "admin");
        Assert.assertTrue(pushed.isEmpty());
        service.refresh();
        Assert.assertEquals(pushed.get(0).getChanged().get(0).value(), "EUR");
    }

    private List<ConfigChanged> subscribe(String group, String application) {
        List<ConfigChanged> pushed = new CopyOnWriteArrayList<>();
        service.subscribe(new ConfigService.Subscriber(group, application, message -> {
            if (message instanceof ConfigChanged changed) {
                pushed.add(changed);
            }
        }));
        return pushed;
    }

    private List<Message> replicate(long since) {
        List<Message> pushed = new CopyOnWriteArrayList<>();
        service.subscribeReplica(new ConfigService.Replica(pushed::add), since);
        return pushed;
    }

    private Map<String, ConfigEntry> seen(String group, String application) {
        return service.visibleTo(group, application).stream()
                .collect(Collectors.toMap(ConfigEntry::name, Function.identity()));
    }
}
