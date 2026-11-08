package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Every configuration, kept in memory and refreshed from the database by revision, and the callers watching them.
 * After each change, from this replica or found in the database, each caller is pushed exactly what changed for it.
 */
final class ConfigService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigStore store;
    private final Clock clock;
    private final Map<Long, ConfigEntry> live = new HashMap<>();
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private long revision;

    /** A caller watching what it sees, and what it was last told. */
    static final class Subscriber {
        private final String group;
        private final String application;
        private final Consumer<ConfigChanged> sink;
        private Map<String, ConfigEntry> seen = Map.of();

        Subscriber(String group, String application, Consumer<ConfigChanged> sink) {
            this.group = group;
            this.application = application;
            this.sink = sink;
        }
    }

    ConfigService(ConfigStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Loads what changed in the database since the last look, and tells the callers it affects. */
    synchronized void refresh() {
        List<StoredConfig> changed = store.changedSince(revision);
        if (changed.isEmpty()) {
            return;
        }
        for (StoredConfig stored : changed) {
            if (stored.deleted()) {
                live.remove(stored.entry().id());
            } else {
                live.put(stored.entry().id(), stored.entry());
            }
            revision = Math.max(revision, stored.revision());
        }
        subscribers.forEach(this::push);
    }

    /** Starts watching for a caller, and returns what it sees now. */
    synchronized List<ConfigEntry> subscribe(Subscriber subscriber) {
        refresh();
        subscriber.seen = ConfigRules.resolve(live.values(), subscriber.group, subscriber.application);
        subscribers.add(subscriber);
        return ConfigRules.ordered(subscriber.seen.values());
    }

    void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    int subscriberCount() {
        return subscribers.size();
    }

    synchronized List<ConfigEntry> listAll() {
        refresh();
        return ConfigRules.ordered(live.values());
    }

    synchronized ConfigEntry create(ConfigEntry requested, String actor) {
        ConfigEntry valid = ConfigRules.validNew(requested);
        ConfigEntry created = store.create(valid, actor, clock.instant());
        LOGGER.info("{} created {}", actor, describe(created));
        refresh();
        return created;
    }

    /** Changes type, value, read-only flag and description. The level, group, application and name stay. */
    synchronized ConfigEntry update(ConfigEntry requested, String actor) {
        refresh();
        if (requested == null || requested.type() == null) {
            throw new ConfigException("Give the configuration a type");
        }
        ConfigEntry current = existing(requested.id());
        ConfigEntry updated = store.update(current.id(), requested.type(), ConfigRules.value(requested),
                requested.readOnly(), ConfigRules.description(requested.description()), actor, clock.instant());
        LOGGER.info("{} updated {}", actor, describe(updated));
        refresh();
        return updated;
    }

    synchronized void delete(long id, String actor) {
        refresh();
        ConfigEntry current = existing(id);
        store.delete(id, actor, clock.instant());
        LOGGER.info("{} deleted {}", actor, describe(current));
        refresh();
    }

    /**
     * Changes the value an application sees for a name: the most specific configuration with that name visible to
     * it. Refused for read-only configurations, which only the portal changes.
     */
    synchronized ConfigEntry setValue(String group, String application, String name, String value) {
        refresh();
        ConfigEntry current = ConfigRules.resolve(live.values(), group, application).get(name);
        if (current == null) {
            throw new ConfigException("No configuration named " + name);
        }
        if (current.readOnly()) {
            throw new ConfigException(name + " is read only");
        }
        String normalized;
        try {
            normalized = current.type().normalize(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
        if (normalized.equals(current.value())) {
            return current;
        }
        String actor = group + "/" + application;
        ConfigEntry updated = store.update(current.id(), current.type(), normalized, false, current.description(),
                actor, clock.instant());
        LOGGER.info("{} set {}", actor, describe(updated));
        refresh();
        return updated;
    }

    private ConfigEntry existing(long id) {
        ConfigEntry current = live.get(id);
        if (current == null) {
            throw new ConfigException("No such configuration");
        }
        return current;
    }

    // Callers hold the lock
    private void push(Subscriber subscriber) {
        Map<String, ConfigEntry> next = ConfigRules.resolve(live.values(), subscriber.group, subscriber.application);
        List<ConfigEntry> changed = new ArrayList<>();
        for (Map.Entry<String, ConfigEntry> entry : next.entrySet()) {
            if (!entry.getValue().equals(subscriber.seen.get(entry.getKey()))) {
                changed.add(entry.getValue());
            }
        }
        List<String> removed = subscriber.seen.keySet().stream().filter(name -> !next.containsKey(name)).toList();
        subscriber.seen = new HashMap<>(next);
        if (changed.isEmpty() && removed.isEmpty()) {
            return;
        }
        try {
            subscriber.sink.accept(new ConfigChanged(ConfigRules.ordered(changed), removed));
        } catch (RuntimeException e) {
            LOGGER.debug("Could not push to {}/{}: {}", subscriber.group, subscriber.application, e.getMessage());
        }
    }

    private static String describe(ConfigEntry entry) {
        String scope = switch (entry.level()) {
            case GLOBAL -> "global";
            case GROUP -> entry.group();
            case APPLICATION -> entry.group() + "/" + entry.application();
        };
        return scope + ":" + entry.name() + (entry.readOnly() ? " (read only)" : "");
    }
}
