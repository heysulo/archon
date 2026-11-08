package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Every configuration in memory, kept in the order the portal shows them: by level, group, application and name.
 * Readers iterate it without locking while changes are applied.
 */
public final class ConfigReplica implements ConfigRowSink {
    private final Object lock = new Object();
    // Replaced whole on a reset, so readers never see a half-built list
    private volatile NavigableMap<String, ConfigEntry> sorted = new ConcurrentSkipListMap<>();
    private Map<Long, ConfigRow> byId = new HashMap<>();
    private volatile long revision;

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public void replace(List<ConfigRow> rows, long upTo) {
        NavigableMap<String, ConfigEntry> nextSorted = new ConcurrentSkipListMap<>();
        Map<Long, ConfigRow> nextById = new HashMap<>();
        long highest = upTo;
        for (ConfigRow row : rows) {
            nextById.put(row.entry().id(), row);
            if (!row.deleted()) {
                nextSorted.put(key(row.entry()), row.entry());
            }
            highest = Math.max(highest, row.revision());
        }
        synchronized (lock) {
            byId = nextById;
            sorted = nextSorted;
            revision = highest;
        }
    }

    @Override
    public void apply(List<ConfigRow> rows, long upTo) {
        synchronized (lock) {
            long highest = Math.max(revision, upTo);
            for (ConfigRow row : rows) {
                ConfigRow previous = byId.get(row.entry().id());
                if (previous != null && previous.revision() >= row.revision()) {
                    continue;
                }
                byId.put(row.entry().id(), row);
                if (previous != null) {
                    sorted.remove(key(previous.entry()));
                }
                if (!row.deleted()) {
                    sorted.put(key(row.entry()), row.entry());
                }
                highest = Math.max(highest, row.revision());
            }
            revision = highest;
        }
    }

    /** Every configuration, in order. A live view: it reflects changes made while iterating it. */
    public Collection<ConfigEntry> entries() {
        return Collections.unmodifiableCollection(sorted.values());
    }

    public int size() {
        return sorted.size();
    }

    public Optional<ConfigEntry> get(long id) {
        synchronized (lock) {
            ConfigRow row = byId.get(id);
            return row == null || row.deleted() ? Optional.empty() : Optional.of(row.entry());
        }
    }

    // Sorts like the portal lists them. Identifiers cannot contain the separator.
    private static String key(ConfigEntry entry) {
        return entry.level().specificity() + "\u0000" + entry.group() + "\u0000" + entry.application() + "\u0000"
                + entry.name();
    }
}
