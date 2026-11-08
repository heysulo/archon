package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushed by archon-config when what a caller sees changes: configurations that now resolve differently, and names
 * that no longer resolve at all.
 */
public class ConfigChanged implements Message {
    private static final long serialVersionUID = 1L;

    private final ArrayList<ConfigEntry> changed;
    private final ArrayList<String> removed;

    public ConfigChanged(List<ConfigEntry> changed, List<String> removed) {
        this.changed = new ArrayList<>(changed);
        this.removed = new ArrayList<>(removed);
    }

    public List<ConfigEntry> getChanged() {
        return List.copyOf(changed);
    }

    public List<String> getRemoved() {
        return List.copyOf(removed);
    }
}
