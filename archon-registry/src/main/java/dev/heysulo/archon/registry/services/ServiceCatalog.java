package dev.heysulo.archon.registry.services;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The services the registry knows about, loaded once from a JSON file. Every registry loads the same file, so a
 * promoted mirror serves the same definitions.
 *
 * <pre>
 * {
 *   "services": [
 *     { "id": "orders", "name": "Order Service", "portOffset": 1,
 *       "group": "Shop", "application": "OrderApp", "mode": "PRIMARY_MIRROR" }
 *   ]
 * }
 * </pre>
 */
public final class ServiceCatalog {
    public static final String SERVICES_FILE_ENV = "ARCHON_SERVICES_FILE";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCatalog.class);

    private final Map<String, ServiceDefinition> definitions;

    private ServiceCatalog(Map<String, ServiceDefinition> definitions) {
        this.definitions = definitions;
    }

    public static ServiceCatalog empty() {
        return new ServiceCatalog(Map.of());
    }

    /** Loads the file named by {@value #SERVICES_FILE_ENV}, or returns an empty catalog when it is not set. */
    public static ServiceCatalog fromEnvironment() {
        String file = System.getenv(SERVICES_FILE_ENV);
        if (file == null || file.isBlank()) {
            LOGGER.warn("{} is not set. No services are defined", SERVICES_FILE_ENV);
            return empty();
        }
        ServiceCatalog catalog = load(Path.of(file));
        LOGGER.info("Loaded {} service definitions from {}", catalog.size(), file);
        return catalog;
    }

    /**
     * @throws IllegalStateException if the file cannot be read or holds an invalid definition. A registry with a
     *                               broken catalog would hand out wrong ports, so it refuses to start instead.
     */
    public static ServiceCatalog load(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read service definitions from " + file, e);
        }
    }

    static ServiceCatalog parse(Reader json) {
        CatalogFile file;
        try {
            file = new Gson().fromJson(json, CatalogFile.class);
        } catch (JsonParseException e) {
            throw new IllegalStateException("Service definitions are not valid JSON: " + e.getMessage(), e);
        }
        Map<String, ServiceDefinition> definitions = new LinkedHashMap<>();
        if (file != null && file.services != null) {
            for (ServiceEntry entry : file.services) {
                ServiceDefinition definition = entry.toDefinition();
                if (definitions.putIfAbsent(definition.id(), definition) != null) {
                    throw new IllegalStateException("Service " + definition.id() + " is defined more than once");
                }
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

    // Gson fills these by reflection
    private static final class CatalogFile {
        private List<ServiceEntry> services;
    }

    private static final class ServiceEntry {
        private String id;
        private String name;
        private Integer portOffset;
        private String group;
        private String application;
        private String mode;

        ServiceDefinition toDefinition() {
            String label = id == null ? "A service" : "Service " + id;
            require(id, label, "id");
            require(name, label, "name");
            require(portOffset, label, "portOffset");
            require(group, label, "group");
            require(application, label, "application");
            require(mode, label, "mode");
            try {
                return new ServiceDefinition(id, name, portOffset, group, application, ServiceMode.valueOf(mode));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(label + " is invalid: " + e.getMessage(), e);
            }
        }

        private static void require(Object value, String label, String field) {
            if (value == null || value instanceof String text && text.isBlank()) {
                throw new IllegalStateException(label + " is missing " + field);
            }
        }
    }
}
