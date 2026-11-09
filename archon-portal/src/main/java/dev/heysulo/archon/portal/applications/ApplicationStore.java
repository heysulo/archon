package dev.heysulo.archon.portal.applications;

import dev.heysulo.archon.portal.auth.AuthException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code application_definitions} table: the applications whose instances may register. The registries own the
 * table and read it again every 10 seconds, so what is stored here reaches them without a restart.
 */
public final class ApplicationStore {
    public static final String ARCHON_GROUP = "Archon";
    /** Archon's own components: always defined, never stored. */
    static final List<String> BUILT_IN = List.of("Config", "Portal", "Registry");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_DESCRIPTION = 255;

    private final DataSource dataSource;

    public ApplicationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Archon's own first, then every stored definition by group and name. */
    public List<ApplicationRecord> list() {
        List<ApplicationRecord> all = new ArrayList<>();
        BUILT_IN.forEach(name -> all.add(new ApplicationRecord(ARCHON_GROUP, name, "Part of Archon", true, null,
                null)));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT app_group, app_name, description, "
                     + "created_at, updated_at FROM application_definitions ORDER BY app_group, app_name");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                all.add(read(result));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read application definitions: " + e.getMessage(), e);
        }
        return all;
    }

    /** Whether instances of the application may register: defined here, or one of Archon's own. */
    public boolean isDefined(String group, String name) {
        if (ARCHON_GROUP.equals(group)) {
            return BUILT_IN.contains(name);
        }
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, group, name).isPresent();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read application definitions: " + e.getMessage(), e);
        }
    }

    public ApplicationRecord create(String group, String name, String description) {
        String validGroup = identifier(group, "group");
        String validName = identifier(name, "name");
        if (ARCHON_GROUP.equals(validGroup)) {
            throw new AuthException(400, "The Archon group holds Archon's own components");
        }
        String validDescription = description(description);
        return write(connection -> {
            if (find(connection, validGroup, validName).isPresent()) {
                throw new AuthException(409, validGroup + ":" + validName + " is already defined");
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO application_definitions "
                    + "(app_group, app_name, description) VALUES (?, ?, ?)")) {
                insert.setString(1, validGroup);
                insert.setString(2, validName);
                insert.setString(3, validDescription);
                insert.executeUpdate();
            }
            return find(connection, validGroup, validName).orElseThrow();
        });
    }

    /** Changes the description; the group and name are the definition's identity. */
    public ApplicationRecord update(String group, String name, String description) {
        String validDescription = description(description);
        return write(connection -> {
            existing(connection, group, name);
            try (PreparedStatement update = connection.prepareStatement("UPDATE application_definitions SET "
                    + "description = ?, updated_at = CURRENT_TIMESTAMP WHERE app_group = ? AND app_name = ?")) {
                update.setString(1, validDescription);
                update.setString(2, group);
                update.setString(3, name);
                update.executeUpdate();
            }
            return find(connection, group, name).orElseThrow();
        });
    }

    /** Removes a definition that owns no service. Callers check that no instance runs first. */
    public void delete(String group, String name) {
        write(connection -> {
            existing(connection, group, name);
            List<String> services = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT id FROM services WHERE "
                    + "app_group = ? AND app_name = ? ORDER BY id")) {
                select.setString(1, group);
                select.setString(2, name);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        services.add(result.getString("id"));
                    }
                }
            }
            if (!services.isEmpty()) {
                throw new AuthException(409, group + ":" + name + " owns " + String.join(", ", services)
                        + ". Delete its services first");
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM application_definitions "
                    + "WHERE app_group = ? AND app_name = ?")) {
                delete.setString(1, group);
                delete.setString(2, name);
                delete.executeUpdate();
            }
            return null;
        });
    }

    private static ApplicationRecord existing(Connection connection, String group, String name) throws SQLException {
        if (ARCHON_GROUP.equals(group)) {
            throw new AuthException(400, "Archon's own components are always defined");
        }
        return find(connection, group, name)
                .orElseThrow(() -> new AuthException(404, group + ":" + name + " is not defined"));
    }

    private static String identifier(String value, String part) {
        String trimmed = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new AuthException(400, "The " + part + " must start with a letter or digit and use only letters, "
                    + "digits, dots, dashes or underscores, up to 128 characters");
        }
        return trimmed;
    }

    private static String description(String description) {
        String trimmed = description == null ? "" : description.trim();
        if (trimmed.length() > MAX_DESCRIPTION) {
            throw new AuthException(400, "A description is at most " + MAX_DESCRIPTION + " characters");
        }
        return trimmed;
    }

    private static Optional<ApplicationRecord> find(Connection connection, String group, String name)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT app_group, app_name, description, "
                + "created_at, updated_at FROM application_definitions WHERE app_group = ? AND app_name = ?")) {
            select.setString(1, group);
            select.setString(2, name);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static ApplicationRecord read(ResultSet result) throws SQLException {
        return new ApplicationRecord(result.getString("app_group"), result.getString("app_name"),
                result.getString("description"), false, time(result.getTimestamp("created_at")),
                time(result.getTimestamp("updated_at")));
    }

    private static String time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T write(Work<T> work) {
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
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new AuthException(409, "That clashes with another definition; reload and try again");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot change application definitions: " + e.getMessage(), e);
        }
    }
}
