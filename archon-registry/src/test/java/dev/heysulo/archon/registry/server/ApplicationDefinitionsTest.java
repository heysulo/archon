package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.registry.applications.ApplicationDefinitions;
import dev.heysulo.archon.registry.constants.ApplicationTermination;
import dev.heysulo.archon.registry.db.Database;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static dev.heysulo.archon.dictionary.sdk.RegistryProtocol.RANK_UNKNOWN;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

/** Only defined applications may register; Archon's own components always may. */
public class ApplicationDefinitionsTest {
    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    // Other tests register freely, as a registry without a database allows
    @AfterMethod
    public void allowEveryone() {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.allowAll());
    }

    @Test
    public void refusesAnUndefinedApplication() {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.of(Set.of("Shop:Cart")));
        FakeConnection stranger = new FakeConnection();
        server.onMessage(null, stranger, registration("Shop", "Checkout", RANK_UNKNOWN));

        Assert.assertEquals(stranger.termination(), ApplicationTermination.REASON_UNDEFINED_APPLICATION);
        Assert.assertNull(stranger.last(ApplicationRegistrationResponseMessage.class), "no rank for it");
    }

    @Test
    public void registersADefinedApplication() {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.of(Set.of("Shop:Cart")));
        FakeConnection cart = new FakeConnection();
        server.onMessage(null, cart, registration("Shop", "Cart", RANK_UNKNOWN));
        Assert.assertNull(cart.termination());
        Assert.assertNotNull(cart.last(ApplicationRegistrationResponseMessage.class));
    }

    // A definition removed while instances run admits none of them again after a reconnect
    @Test
    public void refusesAReconnectOnceTheDefinitionIsGone() {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.of(Set.of()));
        FakeConnection reconnect = new FakeConnection();
        server.onMessage(null, reconnect, registration("Shop", "Cart", 2));
        Assert.assertEquals(reconnect.termination(), ApplicationTermination.REASON_UNDEFINED_APPLICATION);
    }

    @Test
    public void alwaysAdmitsArchonsOwnComponents() {
        ApplicationDefinitions definitions = ApplicationDefinitions.of(Set.of());
        Assert.assertTrue(definitions.isDefined("Archon", "Portal"));
        Assert.assertTrue(definitions.isDefined("Archon", "Config"));
        Assert.assertTrue(definitions.isDefined("Archon", "Registry"));
        Assert.assertFalse(definitions.isDefined("Archon", "Anything"));
        Assert.assertTrue(ApplicationDefinitions.allowAll().isDefined("Any", "Thing"), "no database, no checks");
    }

    // Applications running before definitions existed must keep registering
    @Test
    public void definesApplicationsThatOwnServicesWhenTheTableIsFirstMade() throws Exception {
        Database database = new Database("jdbc:h2:mem:definitions-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORE_UNKNOWN_SETTINGS=TRUE", "sa", "");
        execute(database, "CREATE TABLE services (id VARCHAR(128) PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                + "port_offset INT NOT NULL, app_group VARCHAR(128) NOT NULL, app_name VARCHAR(128) NOT NULL, "
                + "mode VARCHAR(32) NOT NULL)");
        execute(database, "INSERT INTO services VALUES ('ping', 'Ping', 1, 'Group', 'Application', 'PRIMARY_MIRROR')");
        execute(database, "INSERT INTO services VALUES ('echo', 'Echo', 2, 'Group', 'Application', 'LOAD_BALANCE')");
        execute(database, "INSERT INTO services VALUES ('archon-config', 'Configs', 0, 'Archon', 'Config', "
                + "'PRIMARY_MIRROR')");

        ApplicationDefinitions.prepare(database);
        Assert.assertEquals(ApplicationDefinitions.load(database).keys(), Set.of("Group:Application"));

        // Prepared again, as every registry start does, it leaves the definitions alone
        execute(database, "DELETE FROM application_definitions");
        ApplicationDefinitions.prepare(database);
        Assert.assertTrue(ApplicationDefinitions.load(database).keys().isEmpty());
    }

    private static ApplicationRegistrationMessage registration(String group, String name, int rank) {
        ApplicationRegistrationMessage message = new ApplicationRegistrationMessage(name, group, "0.0.0.0");
        message.setRank(rank);
        message.setAuthenticationToken("token");
        return message;
    }

    private static void execute(Database database, String sql) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
