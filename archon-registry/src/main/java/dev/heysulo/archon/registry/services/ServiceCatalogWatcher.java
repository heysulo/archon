package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.registry.applications.ApplicationDefinitions;
import dev.heysulo.archon.registry.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the services and application definitions again every few seconds, so changes made in the portal take
 * effect without restarting the registries. Every registry watches on its own, so a promoted mirror is current
 * too. A failed read keeps the services already loaded.
 */
public final class ServiceCatalogWatcher {
    static final long INTERVAL_SECONDS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCatalogWatcher.class);

    private final Database database;
    private final ServiceRegistry registry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "service-catalog");
        thread.setDaemon(true);
        return thread;
    });
    // The last failure logged, so an outage logs once rather than every interval
    private String lastFailure;

    public ServiceCatalogWatcher(Database database, ServiceRegistry registry) {
        this.database = database;
        this.registry = registry;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::reload, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // Runs on the scheduler. An exception would cancel the schedule, so none may escape.
    void reload() {
        // How many log lines this registry keeps follows configurations, read as often as the services
        dev.heysulo.archon.registry.server.RegistryLogs.reloadSettings(database);
        ServiceCatalog next;
        ApplicationDefinitions definitions;
        try (Connection connection = database.connect()) {
            next = ServiceCatalog.load(connection);
            definitions = ApplicationDefinitions.load(connection);
        } catch (SQLException | RuntimeException e) {
            String failure = e.getMessage();
            if (!Objects.equals(failure, lastFailure)) {
                lastFailure = failure;
                LOGGER.warn("Cannot reload services; keeping the {} loaded: {}", registry.catalog().size(), failure);
            }
            return;
        }
        if (lastFailure != null) {
            lastFailure = null;
            LOGGER.info("Reading services again");
        }
        String defined = describeDefinitionChanges(ApplicationDefinitions.current().keys(), definitions.keys());
        if (!defined.isEmpty()) {
            ApplicationDefinitions.setCurrent(definitions);
            LOGGER.info("Application definitions changed: {}", defined);
        }
        String changes = describeChanges(registry.catalog().all(), next.all());
        if (!changes.isEmpty()) {
            registry.setCatalog(next);
            LOGGER.info("Services changed: {}", changes);
            next.warnAboutSharedOffsets();
        }
    }

    /** What differs between two sets of definitions, as {@code added a; changed b; removed c}, or empty. */
    static String describeChanges(Collection<ServiceDefinition> before, Collection<ServiceDefinition> after) {
        Map<String, ServiceDefinition> old = before.stream()
                .collect(Collectors.toMap(ServiceDefinition::id, Function.identity()));
        Map<String, ServiceDefinition> now = after.stream()
                .collect(Collectors.toMap(ServiceDefinition::id, Function.identity()));
        TreeSet<String> added = new TreeSet<>();
        TreeSet<String> changed = new TreeSet<>();
        TreeSet<String> removed = new TreeSet<>(old.keySet());
        removed.removeAll(now.keySet());
        now.forEach((id, definition) -> {
            ServiceDefinition previous = old.get(id);
            if (previous == null) {
                added.add(id);
            } else if (!previous.equals(definition)) {
                changed.add(id);
            }
        });
        StringBuilder text = new StringBuilder();
        append(text, "added", added);
        append(text, "changed", changed);
        append(text, "removed", removed);
        return text.toString();
    }

    /** Which applications were defined and which no longer are, or empty. */
    static String describeDefinitionChanges(Set<String> before, Set<String> after) {
        TreeSet<String> added = new TreeSet<>(after);
        added.removeAll(before);
        TreeSet<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        StringBuilder text = new StringBuilder();
        append(text, "added", added);
        append(text, "removed", removed);
        return text.toString();
    }

    private static void append(StringBuilder text, String label, TreeSet<String> ids) {
        if (!ids.isEmpty()) {
            text.append(text.isEmpty() ? "" : "; ").append(label).append(' ').append(String.join(", ", ids));
        }
    }
}
