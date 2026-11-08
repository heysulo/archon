package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** A fresh in-memory database in MySQL mode with archon-config's schema. */
final class TestStore implements AutoCloseable {
    private final ConfigDatabase database;

    TestStore() throws InterruptedException, SQLException {
        database = new ConfigDatabase("jdbc:h2:mem:config-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        // The registry owns this table; archon-config only adds its own row
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE services (id VARCHAR(128) PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                    + "port_offset INT NOT NULL, app_group VARCHAR(128) NOT NULL, app_name VARCHAR(128) NOT NULL, "
                    + "mode VARCHAR(32) NOT NULL)");
        }
        database.migrate();
    }

    ConfigStore store() {
        return new ConfigStore(database.dataSource());
    }

    int serviceRows() throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM services WHERE id = 'archon-config'")) {
            result.next();
            return result.getInt(1);
        }
    }

    static ConfigEntry entry(ConfigLevel level, String group, String application, String name, ConfigType type,
            String value, boolean readOnly) {
        return new ConfigEntry(0, level, group, application, name, type, value, readOnly, "", null, null);
    }

    @Override
    public void close() {
        database.close();
    }
}
