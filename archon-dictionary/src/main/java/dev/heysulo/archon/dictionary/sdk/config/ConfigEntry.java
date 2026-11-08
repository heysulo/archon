package dev.heysulo.archon.dictionary.sdk.config;

import java.io.Serial;
import java.io.Serializable;

/**
 * One stored configuration.
 *
 * @param id          the server's identifier, or 0 for one not yet stored
 * @param group       the group it belongs to; empty at the global level
 * @param application the application it belongs to; empty at the global and group levels
 * @param value       the value in its type's canonical form, see {@link ConfigType#normalize}
 * @param readOnly    whether applications are refused when they try to change it; the portal still can
 * @param updatedAt   when it last changed, as an ISO timestamp, or null for one not yet stored
 * @param updatedBy   who last changed it: a portal user, or an application as group/name
 */
public record ConfigEntry(long id, ConfigLevel level, String group, String application, String name, ConfigType type,
                          String value, boolean readOnly, String description, String updatedAt, String updatedBy)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public ConfigEntry withValue(String newValue) {
        return new ConfigEntry(id, level, group, application, name, type, newValue, readOnly, description, updatedAt,
                updatedBy);
    }
}
