package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
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

    // Two replicas share the database. A change made through one reaches the other's callers on its next refresh.
    @Test
    public void picksUpChangesMadeByAnotherReplica() {
        ConfigService other = new ConfigService(database.store(), Clock.systemUTC());
        List<ConfigChanged> pushed = subscribe("Shop", "Cart");
        other.create(entry(GROUP, "Shop", "", "currency", STRING, "EUR", false), "admin");
        Assert.assertTrue(pushed.isEmpty());
        service.refresh();
        Assert.assertEquals(pushed.get(0).getChanged().get(0).value(), "EUR");
    }

    private List<ConfigChanged> subscribe(String group, String application) {
        List<ConfigChanged> pushed = new CopyOnWriteArrayList<>();
        service.subscribe(new ConfigService.Subscriber(group, application, pushed::add));
        return pushed;
    }

    private Map<String, ConfigEntry> seen(String group, String application) {
        return service.subscribe(new ConfigService.Subscriber(group, application, changed -> { }))
                .stream().collect(Collectors.toMap(ConfigEntry::name, Function.identity()));
    }
}
