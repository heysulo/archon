package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/** The answer to a {@link ConfigRequest}: the affected or listed configurations, or why it was refused. */
public class ConfigResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final boolean ok;
    private final String error;
    private final ArrayList<ConfigEntry> entries;

    private ConfigResponse(long requestId, boolean ok, String error, List<ConfigEntry> entries) {
        this.requestId = requestId;
        this.ok = ok;
        this.error = error;
        this.entries = new ArrayList<>(entries);
    }

    public static ConfigResponse ok(long requestId, List<ConfigEntry> entries) {
        return new ConfigResponse(requestId, true, null, entries);
    }

    public static ConfigResponse refused(long requestId, String error) {
        return new ConfigResponse(requestId, false, error, List.of());
    }

    public long getRequestId() {
        return requestId;
    }

    public boolean isOk() {
        return ok;
    }

    public String getError() {
        return error;
    }

    public List<ConfigEntry> getEntries() {
        return List.copyOf(entries);
    }
}
