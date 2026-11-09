package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

/**
 * The application definitions allowed to register: each a group and name, such as {@code Shop:Cart}, from the
 * {@code application_definitions} table. An instance of any other application is told to terminate. Archon's own
 * components are always defined.
 *
 * <p>Without a database, as in development, every application is allowed.
 */
public final class ApplicationDefinitions {
    static final String TABLE = "application_definitions";
    /** Archon's own components, which register like any application but need no definition. */
    static final Set<String> BUILT_IN = Set.of(key(Constants.APPLICATION_GROUP_NAME,
            Constants.APPLICATION_NAME_REGISTRY), key(Constants.APPLICATION_GROUP_NAME, "Portal"),
            key(Constants.APPLICATION_GROUP_NAME, "Config"));
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationDefinitions.class);
    private static volatile ApplicationDefinitions current = allowAll();

    private final Set<String> defined;
    private final boolean enforced;

    private ApplicationDefinitions(Set<String> defined, boolean enforced) {
        this.defined = Set.copyOf(defined);
        this.enforced = enforced;
    }

    /** Every application allowed: no database to read definitions from. */
    public static ApplicationDefinitions allowAll() {
        return new ApplicationDefinitions(Set.of(), false);
    }

    public static ApplicationDefinitions of(Set<String> keys) {
        return new ApplicationDefinitions(keys, true);
    }

    /** The definitions registrations are checked against. */
    public static ApplicationDefinitions current() {
        return current;
    }

    public static void setCurrent(ApplicationDefinitions definitions) {
        current = definitions;
    }

    /**
     * Creates the table if it is missing. The first time, every application that owns a service is defined, so
     * applications already running keep registering.
     */
    public static void prepare(Database database) throws SQLException {
        try (Connection connection = database.connect()) {
            boolean exists;
            try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, TABLE,
                    new String[]{"TABLE"})) {
                exists = tables.next();
            }
            if (exists) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "app_group VARCHAR(128) NOT NULL, "
                        + "app_name VARCHAR(128) NOT NULL, "
                        + "description VARCHAR(255) NOT NULL DEFAULT '', "
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "PRIMARY KEY (app_group, app_name))");
                // IGNORE: another registry starting at the same moment may seed it too
                int seeded = statement.executeUpdate("INSERT IGNORE INTO " + TABLE + " (app_group, app_name, description) "
                        + "SELECT DISTINCT app_group, app_name, 'Defined from the services it owns' FROM services "
                        + "WHERE app_group <> '" + Constants.APPLICATION_GROUP_NAME + "'");
                LOGGER.info("Created {}, defining the {} applications that own services", TABLE, seeded);
            }
        }
    }

    public static ApplicationDefinitions load(Database database) throws SQLException {
        try (Connection connection = database.connect()) {
            return load(connection);
        }
    }

    public static ApplicationDefinitions load(Connection connection) throws SQLException {
        Set<String> keys = new TreeSet<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT app_group, app_name FROM " + TABLE);
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                keys.add(key(result.getString("app_group"), result.getString("app_name")));
            }
        }
        return of(keys);
    }

    /** Whether instances of this application may register. */
    public boolean isDefined(String group, String name) {
        String key = key(group, name);
        return !enforced || BUILT_IN.contains(key) || defined.contains(key);
    }

    public boolean isEnforced() {
        return enforced;
    }

    /** The defined applications as {@code Group:Name}, Archon's own not included. */
    public Set<String> keys() {
        return defined;
    }

    public static String key(String group, String name) {
        return group + ":" + name;
    }
}
