package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Configurations in the archon schema. Every write takes the next revision in the same transaction, so a reader that
 * remembers the last revision it saw can fetch exactly what changed since, deletions included.
 */
final class ConfigStore {
    private static final String COLUMNS = "id, level, app_group, app_name, name, value_type, config_value, read_only, "
            + "description, deleted, revision, updated_at, updated_by";

    private final DataSource dataSource;

    ConfigStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Every configuration changed after the revision, oldest first, deleted ones included. */
    List<StoredConfig> changedSince(long revision) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM configurations WHERE revision > ? ORDER BY revision")) {
            select.setLong(1, revision);
            try (ResultSet result = select.executeQuery()) {
                List<StoredConfig> changed = new ArrayList<>();
                while (result.next()) {
                    changed.add(read(result));
                }
                return changed;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read configurations: " + e.getMessage(), e);
        }
    }

    /**
     * Stores a new configuration, or brings back a deleted one with the same identifier.
     *
     * @throws ConfigException if one with this identifier exists
     */
    ConfigEntry create(ConfigEntry entry, String actor, Instant now) {
        return transaction(connection -> {
            long revision = nextRevision(connection);
            Optional<StoredConfig> existing = findByIdentifier(connection, entry);
            if (existing.isPresent() && !existing.get().deleted()) {
                throw new ConfigException("A configuration with this level, group, application and name exists");
            }
            long id;
            if (existing.isPresent()) {
                id = existing.get().entry().id();
                try (PreparedStatement revive = connection.prepareStatement("UPDATE configurations SET value_type = ?, "
                        + "config_value = ?, read_only = ?, description = ?, deleted = FALSE, revision = ?, "
                        + "created_at = ?, updated_at = ?, updated_by = ? WHERE id = ?")) {
                    revive.setString(1, entry.type().name());
                    revive.setString(2, entry.value());
                    revive.setBoolean(3, entry.readOnly());
                    revive.setString(4, entry.description());
                    revive.setLong(5, revision);
                    setTime(revive, 6, now);
                    setTime(revive, 7, now);
                    revive.setString(8, actor);
                    revive.setLong(9, id);
                    revive.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO configurations (level, "
                        + "app_group, app_name, name, value_type, config_value, read_only, description, deleted, "
                        + "revision, created_at, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, entry.level().name());
                    insert.setString(2, entry.group());
                    insert.setString(3, entry.application());
                    insert.setString(4, entry.name());
                    insert.setString(5, entry.type().name());
                    insert.setString(6, entry.value());
                    insert.setBoolean(7, entry.readOnly());
                    insert.setString(8, entry.description());
                    insert.setLong(9, revision);
                    setTime(insert, 10, now);
                    setTime(insert, 11, now);
                    insert.setString(12, actor);
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
            }
            return findById(connection, id).orElseThrow().entry();
        });
    }

    /** Changes a configuration's type, value, read-only flag and description. Its identifier never changes. */
    ConfigEntry update(long id, ConfigType type, String value, boolean readOnly, String description, String actor,
            Instant now) {
        return transaction(connection -> {
            long revision = nextRevision(connection);
            try (PreparedStatement update = connection.prepareStatement("UPDATE configurations SET value_type = ?, "
                    + "config_value = ?, read_only = ?, description = ?, revision = ?, updated_at = ?, updated_by = ? "
                    + "WHERE id = ? AND deleted = FALSE")) {
                update.setString(1, type.name());
                update.setString(2, value);
                update.setBoolean(3, readOnly);
                update.setString(4, description);
                update.setLong(5, revision);
                setTime(update, 6, now);
                update.setString(7, actor);
                update.setLong(8, id);
                if (update.executeUpdate() == 0) {
                    throw new ConfigException("No such configuration");
                }
            }
            return findById(connection, id).orElseThrow().entry();
        });
    }

    void delete(long id, String actor, Instant now) {
        transaction(connection -> {
            long revision = nextRevision(connection);
            try (PreparedStatement delete = connection.prepareStatement("UPDATE configurations SET deleted = TRUE, "
                    + "revision = ?, updated_at = ?, updated_by = ? WHERE id = ? AND deleted = FALSE")) {
                delete.setLong(1, revision);
                setTime(delete, 2, now);
                delete.setString(3, actor);
                delete.setLong(4, id);
                if (delete.executeUpdate() == 0) {
                    throw new ConfigException("No such configuration");
                }
            }
            return null;
        });
    }

    // The single revision row is locked until the transaction ends, so concurrent writers take turns
    private static long nextRevision(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE configuration_revision SET revision = revision + 1 WHERE id = 1");
            try (ResultSet result =
                         statement.executeQuery("SELECT revision FROM configuration_revision WHERE id = 1")) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static Optional<StoredConfig> findById(Connection connection, long id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM configurations WHERE id = ?")) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<StoredConfig> findByIdentifier(Connection connection, ConfigEntry entry)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT " + COLUMNS + " FROM configurations "
                + "WHERE level = ? AND app_group = ? AND app_name = ? AND name = ?")) {
            select.setString(1, entry.level().name());
            select.setString(2, entry.group());
            select.setString(3, entry.application());
            select.setString(4, entry.name());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static StoredConfig read(ResultSet result) throws SQLException {
        LocalDateTime updatedAt = result.getObject("updated_at", LocalDateTime.class);
        ConfigEntry entry = new ConfigEntry(result.getLong("id"), ConfigLevel.valueOf(result.getString("level")),
                result.getString("app_group"), result.getString("app_name"), result.getString("name"),
                ConfigType.valueOf(result.getString("value_type")), result.getString("config_value"),
                result.getBoolean("read_only"), result.getString("description"),
                updatedAt.toInstant(ZoneOffset.UTC).toString(), result.getString("updated_by"));
        return new StoredConfig(entry, result.getBoolean("deleted"), result.getLong("revision"));
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T transaction(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write configurations: " + e.getMessage(), e);
        }
    }

    private static void setTime(PreparedStatement statement, int index, Instant time) throws SQLException {
        statement.setObject(index, LocalDateTime.ofInstant(time, ZoneOffset.UTC));
    }
}
