package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.registry.applications.ApplicationDefinitions;
import dev.heysulo.archon.registry.db.Database;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

public class ServiceCatalogWatcherTest {
    private Database database;
    private ServiceRegistry registry;
    private ServiceCatalogWatcher watcher;

    @BeforeMethod
    public void setUp() throws Exception {
        // The registry passes MySQL connection settings, which H2 must ignore
        database = new Database("jdbc:h2:mem:registry-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORE_UNKNOWN_SETTINGS=TRUE", "sa", "");
        execute("CREATE TABLE services (id VARCHAR(128) PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                + "port_offset INT NOT NULL, app_group VARCHAR(128) NOT NULL, app_name VARCHAR(128) NOT NULL, "
                + "mode VARCHAR(32) NOT NULL)");
        execute("INSERT INTO services VALUES ('ping', 'Ping', 0, 'Group', 'Application', 'PRIMARY_MIRROR')");
        execute("CREATE TABLE application_definitions (app_group VARCHAR(128) NOT NULL, app_name VARCHAR(128) NOT "
                + "NULL, PRIMARY KEY (app_group, app_name))");
        execute("INSERT INTO application_definitions VALUES ('Group', 'Application')");
        registry = new ServiceRegistry();
        registry.setCatalog(ServiceCatalog.load(database));
        watcher = new ServiceCatalogWatcher(database, registry);
    }

    // Reloading also loads definitions, which would refuse other tests' made-up applications
    @AfterMethod
    public void allowEveryone() {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.allowAll());
    }

    @Test
    public void picksUpAddedChangedAndRemovedServices() throws Exception {
        execute("INSERT INTO services VALUES ('search', 'Search', 1, 'Shop', 'Search', 'LOAD_BALANCE')");
        watcher.reload();
        Assert.assertTrue(registry.catalog().find("search").isPresent());

        execute("UPDATE services SET port_offset = 5 WHERE id = 'ping'");
        execute("DELETE FROM services WHERE id = 'search'");
        watcher.reload();
        Assert.assertEquals(registry.catalog().find("ping").orElseThrow().portOffset(), 5);
        Assert.assertTrue(registry.catalog().find("search").isEmpty());
    }

    // A database outage must not leave the registry refusing every service
    @Test
    public void keepsTheLoadedServicesWhenTheDatabaseFails() throws Exception {
        execute("DROP TABLE services");
        watcher.reload();
        Assert.assertTrue(registry.catalog().find("ping").isPresent());
    }

    // A row the registry cannot use is refused as a whole; the services already loaded stay
    @Test
    public void keepsTheLoadedServicesWhenARowIsInvalid() throws Exception {
        execute("INSERT INTO services VALUES ('bad', 'Bad', 2, 'Shop', 'Bad', 'SOMETIMES')");
        watcher.reload();
        Assert.assertEquals(registry.catalog().size(), 1);
    }

    // Stored some other way, a shared offset is still served, so running applications keep working
    @Test
    public void stillServesServicesSharingAnOffset() throws Exception {
        execute("INSERT INTO services VALUES ('echo', 'Echo', 0, 'Shop', 'Echo', 'PRIMARY_MIRROR')");
        watcher.reload();
        Assert.assertTrue(registry.catalog().find("echo").isPresent());
        Assert.assertTrue(registry.catalog().find("ping").isPresent());
    }

    @Test
    public void picksUpApplicationDefinitions() throws Exception {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.load(database));
        execute("INSERT INTO application_definitions VALUES ('Shop', 'Cart')");
        watcher.reload();
        Assert.assertTrue(ApplicationDefinitions.current().isDefined("Shop", "Cart"));
        execute("DELETE FROM application_definitions WHERE app_group = 'Group'");
        watcher.reload();
        Assert.assertFalse(ApplicationDefinitions.current().isDefined("Group", "Application"));
    }

    @Test
    public void describesWhatChanged() {
        ServiceDefinition ping = new ServiceDefinition("ping", "Ping", 0, "Group", "Application",
                ServiceMode.PRIMARY_MIRROR);
        ServiceDefinition moved = new ServiceDefinition("ping", "Ping", 3, "Group", "Application",
                ServiceMode.PRIMARY_MIRROR);
        ServiceDefinition search = new ServiceDefinition("search", "Search", 1, "Shop", "Search",
                ServiceMode.LOAD_BALANCE);
        Assert.assertEquals(ServiceCatalogWatcher.describeChanges(List.of(ping), List.of(ping)), "");
        Assert.assertEquals(ServiceCatalogWatcher.describeChanges(List.of(ping), List.of(moved, search)),
                "added search; changed ping");
        Assert.assertEquals(ServiceCatalogWatcher.describeChanges(List.of(ping, search), List.of()),
                "removed ping, search");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
