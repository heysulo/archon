package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.registry.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The services the registry knows about, from the {@code services} table of the archon schema: loaded at startup,
 * then read again now and then by {@link ServiceCatalogWatcher}. Every registry reads the same table, so a promoted
 * mirror serves the same definitions.
 */
public final class ServiceCatalog {
    static final String QUERY = "SELECT id, name, port_offset, app_group, app_name, mode FROM services ORDER BY id";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCatalog.class);
    private static final int LOAD_ATTEMPTS = 10;
    private static final long LOAD_RETRY_DELAY_MILLIS = 3_000;

    private final Map<String, ServiceDefinition> definitions;

    private ServiceCatalog(Map<String, ServiceDefinition> definitions) {
        this.definitions = definitions;
    }

    public static ServiceCatalog empty() {
        return new ServiceCatalog(Map.of());
    }

    /**
     * Loads the services from the database configured in the environment, or returns an empty catalog when none is.
     * Retries for a while, as the database may still be starting.
     *
     * @throws IllegalStateException if the database stays unreachable or holds an invalid service. A registry
     *                               without its services would refuse every one, so it does not start instead.
     */
    public static ServiceCatalog fromEnvironment() throws InterruptedException {
        Optional<Database> database = Database.fromEnvironment();
        if (database.isEmpty()) {
            LOGGER.warn("{} is not set. No services are defined", Database.URL_ENV);
            return empty();
        }
        return load(database.get());
    }

    public static ServiceCatalog load(Database database) throws InterruptedException {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= LOAD_ATTEMPTS; attempt++) {
            try (Connection connection = database.connect()) {
                ServiceCatalog catalog = load(connection);
                LOGGER.info("Loaded {} service definitions from {}", catalog.size(), database);
                return catalog;
            } catch (SQLException e) {
                lastFailure = e;
                LOGGER.warn("Cannot load services from {} (attempt {} of {}): {}", database, attempt, LOAD_ATTEMPTS,
                        e.getMessage());
                if (attempt < LOAD_ATTEMPTS) {
                    Thread.sleep(LOAD_RETRY_DELAY_MILLIS);
                }
            }
        }
        throw new IllegalStateException("Cannot load services from " + database, lastFailure);
    }

    static ServiceCatalog load(Connection connection) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(QUERY);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new Row(result.getString("id"), result.getString("name"),
                        result.getObject("port_offset", Integer.class), result.getString("app_group"),
                        result.getString("app_name"), result.getString("mode")));
            }
        }
        return fromRows(rows);
    }

    static ServiceCatalog fromRows(List<Row> rows) {
        Map<String, ServiceDefinition> definitions = new LinkedHashMap<>();
        for (Row row : rows) {
            ServiceDefinition definition = row.toDefinition();
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Service " + definition.id() + " is defined more than once");
            }
        }
        return new ServiceCatalog(Map.copyOf(definitions));
    }

    public Optional<ServiceDefinition> find(String serviceId) {
        return Optional.ofNullable(serviceId).map(definitions::get);
    }

    public Collection<ServiceDefinition> all() {
        return definitions.values();
    }

    public int size() {
        return definitions.size();
    }

    /** One row of the services table, checked before it becomes a definition. */
    record Row(String id, String name, Integer portOffset, String group, String application, String mode) {

        ServiceDefinition toDefinition() {
            String label = id == null ? "A service" : "Service " + id;
            require(id, label, "id");
            require(name, label, "name");
            require(portOffset, label, "port_offset");
            require(group, label, "app_group");
            require(application, label, "app_name");
            require(mode, label, "mode");
            try {
                return new ServiceDefinition(id, name, portOffset, group, application, ServiceMode.valueOf(mode));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(label + " is invalid: " + e.getMessage(), e);
            }
        }

        private static void require(Object value, String label, String column) {
            if (value == null || value instanceof String text && text.isBlank()) {
                throw new IllegalStateException(label + " is missing " + column);
            }
        }
    }
}
