package dev.heysulo.archon.portal.services;

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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code services} table the registries read. The registries own the table and re-read it every few seconds,
 * so what is stored here reaches them without a restart. Services owned by Archon itself cannot be changed here:
 * removing {@code archon-config}, say, would stop every application receiving configurations.
 */
public final class ServiceStore {
    public static final String ARCHON_GROUP = "Archon";
    static final Set<String> MODES = Set.of("PRIMARY_MIRROR", "LOAD_BALANCE");
    static final int MAX_PORT_OFFSET = 65534;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_NAME = 255;
    private static final String COLUMNS = "id, name, port_offset, app_group, app_name, mode, created_at, updated_at";

    private final DataSource dataSource;

    public ServiceStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<ServiceRecord> list() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM services ORDER BY id");
             ResultSet result = select.executeQuery()) {
            List<ServiceRecord> services = new ArrayList<>();
            while (result.next()) {
                services.add(read(result));
            }
            return services;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read services: " + e.getMessage(), e);
        }
    }

    public ServiceRecord create(ServiceRecord requested) {
        ServiceRecord service = valid(requested);
        if (service.managedByArchon()) {
            throw new AuthException(400, "Services of the Archon group are managed by Archon");
        }
        return write(connection -> {
            if (find(connection, service.id()).isPresent()) {
                throw new AuthException(409, "A service with the id " + service.id() + " exists");
            }
            refuseSharedPort(connection, service);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO services (id, name, "
                    + "port_offset, app_group, app_name, mode) VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, service.id());
                insert.setString(2, service.name());
                insert.setInt(3, service.portOffset());
                insert.setString(4, service.group());
                insert.setString(5, service.application());
                insert.setString(6, service.mode());
                insert.executeUpdate();
            }
            return find(connection, service.id()).orElseThrow();
        });
    }

    /** Changes everything but the id, which applications look the service up by. */
    public ServiceRecord update(String id, ServiceRecord requested) {
        ServiceRecord service = valid(new ServiceRecord(id, requested.name(), requested.portOffset(),
                requested.group(), requested.application(), requested.mode(), false, null, null));
        return write(connection -> {
            ServiceRecord current = find(connection, id)
                    .orElseThrow(() -> new AuthException(404, "No service with the id " + id));
            if (current.managedByArchon() || service.managedByArchon()) {
                throw new AuthException(400, "Services of the Archon group are managed by Archon");
            }
            refuseSharedPort(connection, service);
            try (PreparedStatement update = connection.prepareStatement("UPDATE services SET name = ?, "
                    + "port_offset = ?, app_group = ?, app_name = ?, mode = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ?")) {
                update.setString(1, service.name());
                update.setInt(2, service.portOffset());
                update.setString(3, service.group());
                update.setString(4, service.application());
                update.setString(5, service.mode());
                update.setString(6, id);
                update.executeUpdate();
            }
            return find(connection, id).orElseThrow();
        });
    }

    public void delete(String id) {
        write(connection -> {
            ServiceRecord current = find(connection, id)
                    .orElseThrow(() -> new AuthException(404, "No service with the id " + id));
            if (current.managedByArchon()) {
                throw new AuthException(400, "Services of the Archon group are managed by Archon");
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM services WHERE id = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            return null;
        });
    }

    /** Trims and checks a requested service, as the registry would refuse it otherwise. */
    static ServiceRecord valid(ServiceRecord requested) {
        if (requested == null) {
            throw new AuthException(400, "Describe the service");
        }
        String id = identifier(requested.id(), "id");
        String name = requested.name() == null ? "" : requested.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new AuthException(400, "Give the service a name of up to " + MAX_NAME + " characters");
        }
        if (requested.portOffset() < 0 || requested.portOffset() > MAX_PORT_OFFSET) {
            throw new AuthException(400, "The port offset must be between 0 and " + MAX_PORT_OFFSET);
        }
        String group = identifier(requested.group(), "group");
        String application = identifier(requested.application(), "application");
        String mode = requested.mode() == null ? "" : requested.mode().trim();
        if (!MODES.contains(mode)) {
            throw new AuthException(400, "The mode must be PRIMARY_MIRROR or LOAD_BALANCE");
        }
        return new ServiceRecord(id, name, requested.portOffset(), group, application, mode,
                ARCHON_GROUP.equals(group), null, null);
    }

    private static String identifier(String value, String part) {
        String trimmed = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new AuthException(400, "The " + part + " must start with a letter or digit and use only letters, "
                    + "digits, dots, dashes or underscores, up to 128 characters");
        }
        return trimmed;
    }

    // Each port offset belongs to one service, across every application, so a port names exactly one service
    private static void refuseSharedPort(Connection connection, ServiceRecord service) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM services WHERE port_offset = ? "
                + "AND id <> ?")) {
            select.setInt(1, service.portOffset());
            select.setString(2, service.id());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    throw new AuthException(409, "Port offset " + service.portOffset() + " is taken by "
                            + result.getString("id"));
                }
            }
        }
    }

    private static Optional<ServiceRecord> find(Connection connection, String id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM services WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static ServiceRecord read(ResultSet result) throws SQLException {
        String group = result.getString("app_group");
        return new ServiceRecord(result.getString("id"), result.getString("name"), result.getInt("port_offset"),
                group, result.getString("app_name"), result.getString("mode"), ARCHON_GROUP.equals(group),
                time(result.getTimestamp("created_at")), time(result.getTimestamp("updated_at")));
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
            // Another change won a race with the checks above
            throw new AuthException(409, "That clashes with another service; reload and try again");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot change services: " + e.getMessage(), e);
        }
    }
}
