package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigChunks;
import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRows;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigSnapshot;
import dev.heysulo.archon.sdk.config.ConfigRowSink;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every configuration, kept in memory, and everyone listening.
 *
 * <p>The primary writes to the database, then reads back what changed by revision and passes it on: every row to
 * the replicas (its mirrors and the portal), and to each application only the names it sees whose value changed.
 * Mirrors receive the same rows through {@link ConfigRowSink}.
 *
 * <p>Configurations are kept by who sees them: global, per group and per application. What one application sees is
 * then found from its own three maps, and a change only reaches the applications it can affect, so the work per
 * change does not grow with the number of configurations.
 */
final class ConfigService implements ConfigRowSink {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigStore store;
    private final Clock clock;
    private final int maxChunkBytes;
    // Every row ever stored, deleted ones too, so a replica catching up hears about deletions
    private final Map<Long, ConfigRow> rows = new HashMap<>();
    private final Map<String, ConfigEntry> global = new HashMap<>();
    private final Map<String, Map<String, ConfigEntry>> groups = new HashMap<>();
    private final Map<String, Map<String, ConfigEntry>> applications = new HashMap<>();
    private final Set<Subscriber> subscribers = new LinkedHashSet<>();
    private final Map<String, Set<Subscriber>> subscribersByGroup = new HashMap<>();
    private final Map<String, Set<Subscriber>> subscribersByApplication = new HashMap<>();
    private final Set<Replica> replicas = new LinkedHashSet<>();
    private long revision;
    private int live;

    /** An application instance watching what it sees. */
    static final class Subscriber {
        private final String group;
        private final String application;
        private final Consumer<Message> sink;

        Subscriber(String group, String application, Consumer<Message> sink) {
            this.group = group;
            this.application = application;
            this.sink = sink;
        }
    }

    /** A mirror or portal receiving every row. */
    static final class Replica {
        private final Consumer<Message> sink;

        Replica(Consumer<Message> sink) {
            this.sink = sink;
        }
    }

    ConfigService(ConfigStore store, Clock clock) {
        this(store, clock, ConfigChunks.MAX_CHUNK_BYTES);
    }

    ConfigService(ConfigStore store, Clock clock, int maxChunkBytes) {
        this.store = store;
        this.clock = clock;
        this.maxChunkBytes = maxChunkBytes;
    }

    // ---------------- keeping up ----------------

    /** Reads what changed in the database since the last revision held, and passes it on. */
    synchronized void refresh() {
        apply(store.changedSince(revision));
    }

    @Override
    public synchronized long revision() {
        return revision;
    }

    /** Everything, from the primary, replacing what this mirror held. */
    @Override
    public synchronized void replace(List<ConfigRow> all, long upTo) {
        rows.clear();
        global.clear();
        groups.clear();
        applications.clear();
        live = 0;
        revision = 0;
        apply(all);
        revision = Math.max(revision, upTo);
    }

    /** Changes from the primary, as it made them. */
    @Override
    public synchronized void apply(List<ConfigRow> changed, long upTo) {
        apply(changed);
        revision = Math.max(revision, upTo);
    }

    // Callers hold the lock
    private void apply(List<ConfigRow> changed) {
        List<ConfigRow> applied = new ArrayList<>();
        for (ConfigRow row : changed) {
            ConfigRow previous = rows.get(row.entry().id());
            if (previous != null && previous.revision() >= row.revision()) {
                continue;
            }
            rows.put(row.entry().id(), row);
            if (previous != null && !previous.deleted()) {
                unindex(previous.entry());
            }
            if (!row.deleted()) {
                index(row.entry());
            }
            revision = Math.max(revision, row.revision());
            applied.add(row);
        }
        if (applied.isEmpty()) {
            return;
        }
        pushToReplicas(applied);
        pushToApplications(applied);
    }

    // ---------------- listeners ----------------

    /** Sends the application what it sees now, in pieces, then keeps it up to date. */
    synchronized void subscribe(Subscriber subscriber) {
        List<ConfigEntry> visible = visibleTo(subscriber.group, subscriber.application);
        List<List<ConfigEntry>> chunks = ConfigChunks.split(visible, ConfigChunks::size, maxChunkBytes);
        for (int i = 0; i < chunks.size(); i++) {
            subscriber.sink.accept(new ConfigSnapshot(chunks.get(i), i == chunks.size() - 1));
        }
        // Added after the snapshot, under the same lock, so no change can arrive ahead of it
        subscribers.add(subscriber);
        subscribersByGroup.computeIfAbsent(subscriber.group, key -> new LinkedHashSet<>()).add(subscriber);
        subscribersByApplication.computeIfAbsent(key(subscriber.group, subscriber.application),
                key -> new LinkedHashSet<>()).add(subscriber);
    }

    /**
     * Sends a replica what it missed since {@code since}, in pieces, then every change. A replica with nothing, or
     * one ahead of this server (the database was replaced), gets everything instead.
     */
    synchronized void subscribeReplica(Replica replica, long since) {
        boolean everything = since <= 0 || since > revision;
        List<ConfigRow> missed = rows.values().stream()
                .filter(row -> everything ? !row.deleted() : row.revision() > since)
                .sorted(Comparator.comparingLong(ConfigRow::revision))
                .toList();
        List<List<ConfigRow>> chunks = ConfigChunks.split(missed, ConfigChunks::size, maxChunkBytes);
        for (int i = 0; i < chunks.size(); i++) {
            replica.sink.accept(new ConfigRows(chunks.get(i), revision, everything && i == 0,
                    i == chunks.size() - 1));
        }
        replicas.add(replica);
    }

    synchronized void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
        removeFrom(subscribersByGroup, subscriber.group, subscriber);
        removeFrom(subscribersByApplication, key(subscriber.group, subscriber.application), subscriber);
    }

    synchronized void unsubscribe(Replica replica) {
        replicas.remove(replica);
    }

    synchronized int subscriberCount() {
        return subscribers.size();
    }

    synchronized int replicaCount() {
        return replicas.size();
    }

    // ---------------- reading ----------------

    /** What an application sees: per name, its own configuration, else its group's, else the global one. */
    synchronized List<ConfigEntry> visibleTo(String group, String application) {
        Map<String, ConfigEntry> visible = new HashMap<>(global);
        visible.putAll(groups.getOrDefault(group, Map.of()));
        visible.putAll(applications.getOrDefault(key(group, application), Map.of()));
        return ConfigRules.ordered(visible.values());
    }

    synchronized List<ConfigEntry> listAll() {
        List<ConfigEntry> all = new ArrayList<>(live);
        rows.values().stream().filter(row -> !row.deleted()).forEach(row -> all.add(row.entry()));
        return ConfigRules.ordered(all);
    }

    synchronized int size() {
        return live;
    }

    // ---------------- changing ----------------

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
        ConfigEntry current = effective(group, application, name);
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
        ConfigRow row = rows.get(id);
        if (row == null || row.deleted()) {
            throw new ConfigException("No such configuration");
        }
        return row.entry();
    }

    // ---------------- pushing ----------------

    // Callers hold the lock
    private void pushToReplicas(List<ConfigRow> applied) {
        if (replicas.isEmpty()) {
            return;
        }
        List<List<ConfigRow>> chunks = ConfigChunks.split(applied, ConfigChunks::size, maxChunkBytes);
        for (Replica replica : replicas) {
            for (int i = 0; i < chunks.size(); i++) {
                send(replica.sink, new ConfigRows(chunks.get(i), revision, false, i == chunks.size() - 1));
            }
        }
    }

    // Callers hold the lock. For each application a changed row can reach, re-resolves only the changed names.
    private void pushToApplications(List<ConfigRow> applied) {
        if (subscribers.isEmpty()) {
            return;
        }
        // Per application: each changed name, and the most specific level it changed at
        Map<Subscriber, Map<String, Integer>> touched = new IdentityHashMap<>();
        for (ConfigRow row : applied) {
            ConfigEntry entry = row.entry();
            for (Subscriber subscriber : reachedBy(entry)) {
                touched.computeIfAbsent(subscriber, key -> new HashMap<>())
                        .merge(entry.name(), entry.level().specificity(), Math::max);
            }
        }
        touched.forEach((subscriber, names) -> {
            List<ConfigEntry> changed = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            names.forEach((name, changedAt) -> {
                ConfigEntry now = effective(subscriber.group, subscriber.application, name);
                if (now == null) {
                    removed.add(name);
                } else if (now.level().specificity() <= changedAt) {
                    // The changed configuration itself, or a less specific one now showing through
                    changed.add(now);
                }
                // Otherwise a more specific configuration hides the change, and what it sees is the same
            });
            if (!changed.isEmpty() || !removed.isEmpty()) {
                send(subscriber.sink, new ConfigChanged(ConfigRules.ordered(changed), removed));
            }
        });
    }

    private Collection<Subscriber> reachedBy(ConfigEntry entry) {
        return switch (entry.level()) {
            case GLOBAL -> subscribers;
            case GROUP -> subscribersByGroup.getOrDefault(entry.group(), Set.of());
            case APPLICATION -> subscribersByApplication.getOrDefault(key(entry.group(), entry.application()),
                    Set.of());
        };
    }

    private static void send(Consumer<Message> sink, Message message) {
        try {
            sink.accept(message);
        } catch (RuntimeException e) {
            LOGGER.debug("Could not push a configuration change: {}", e.getMessage());
        }
    }

    // ---------------- indexes ----------------

    private ConfigEntry effective(String group, String application, String name) {
        ConfigEntry own = applications.getOrDefault(key(group, application), Map.of()).get(name);
        if (own != null) {
            return own;
        }
        ConfigEntry fromGroup = groups.getOrDefault(group, Map.of()).get(name);
        return fromGroup != null ? fromGroup : global.get(name);
    }

    private void index(ConfigEntry entry) {
        scope(entry, true).put(entry.name(), entry);
        live++;
    }

    private void unindex(ConfigEntry entry) {
        Map<String, ConfigEntry> scope = scope(entry, false);
        if (scope != null && scope.remove(entry.name()) != null) {
            live--;
            if (scope.isEmpty() && entry.level() != ConfigLevel.GLOBAL) {
                (entry.level() == ConfigLevel.GROUP ? groups : applications).remove(scopeKey(entry));
            }
        }
    }

    private Map<String, ConfigEntry> scope(ConfigEntry entry, boolean create) {
        return switch (entry.level()) {
            case GLOBAL -> global;
            case GROUP -> create ? groups.computeIfAbsent(entry.group(), key -> new HashMap<>())
                    : groups.get(entry.group());
            case APPLICATION -> create ? applications.computeIfAbsent(scopeKey(entry), key -> new HashMap<>())
                    : applications.get(scopeKey(entry));
        };
    }

    private static String scopeKey(ConfigEntry entry) {
        return entry.level() == ConfigLevel.GROUP ? entry.group() : key(entry.group(), entry.application());
    }

    // Group and application names cannot contain a slash, so this cannot collide
    private static String key(String group, String application) {
        return group + "/" + application;
    }

    private static <T> void removeFrom(Map<String, Set<T>> index, String key, T value) {
        Set<T> set = index.get(key);
        if (set != null && set.remove(value) && set.isEmpty()) {
            index.remove(key);
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
