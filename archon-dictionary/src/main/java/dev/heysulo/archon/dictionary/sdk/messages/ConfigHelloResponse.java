package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/** Whether archon-config accepted the caller, and if so every configuration it can see, most specific per name. */
public class ConfigHelloResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final boolean accepted;
    private final String reason;
    private final ArrayList<ConfigEntry> entries;

    private ConfigHelloResponse(boolean accepted, String reason, List<ConfigEntry> entries) {
        this.accepted = accepted;
        this.reason = reason;
        this.entries = new ArrayList<>(entries);
    }

    public static ConfigHelloResponse accepted(List<ConfigEntry> entries) {
        return new ConfigHelloResponse(true, null, entries);
    }

    public static ConfigHelloResponse refused(String reason) {
        return new ConfigHelloResponse(false, reason, List.of());
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }

    public List<ConfigEntry> getEntries() {
        return List.copyOf(entries);
    }
}
