package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * One piece of what an application sees, most specific per name, sent after an accepted {@link ConfigHello}. The
 * application uses the pieces only once the last one arrives, so it never sees half a snapshot.
 */
public class ConfigSnapshot implements Message {
    private static final long serialVersionUID = 1L;

    private final ArrayList<ConfigEntry> entries;
    private final boolean last;

    public ConfigSnapshot(List<ConfigEntry> entries, boolean last) {
        this.entries = new ArrayList<>(entries);
        this.last = last;
    }

    public List<ConfigEntry> getEntries() {
        return List.copyOf(entries);
    }

    public boolean isLast() {
        return last;
    }
}
