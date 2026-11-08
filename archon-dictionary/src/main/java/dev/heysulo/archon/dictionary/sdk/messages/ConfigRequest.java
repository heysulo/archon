package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.databridge.core.common.Message;

/**
 * A request to archon-config after the hello. Applications may only {@link Operation#SET}; the rest are for the
 * portal, whose identity archon-config checks.
 */
public class ConfigRequest implements Message {
    private static final long serialVersionUID = 1L;

    public enum Operation {
        /** Changes the value of a configuration the caller sees, unless it is read only. */
        SET,
        /** Every configuration at every level. Portal only. */
        LIST_ALL,
        CREATE,
        UPDATE,
        DELETE
    }

    private final long requestId;
    private final Operation operation;
    private final ConfigEntry entry;
    private final String actor;

    /**
     * @param entry for SET, the name and new value; for CREATE and UPDATE, the whole configuration; for DELETE, its id
     * @param actor who the portal is acting for, recorded as the changer; ignored from applications
     */
    public ConfigRequest(long requestId, Operation operation, ConfigEntry entry, String actor) {
        this.requestId = requestId;
        this.operation = operation;
        this.entry = entry;
        this.actor = actor;
    }

    public long getRequestId() {
        return requestId;
    }

    public Operation getOperation() {
        return operation;
    }

    public ConfigEntry getEntry() {
        return entry;
    }

    public String getActor() {
        return actor;
    }
}
