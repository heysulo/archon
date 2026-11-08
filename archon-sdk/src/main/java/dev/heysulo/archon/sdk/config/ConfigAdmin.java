package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;

/**
 * Changes any configuration at any level. archon-config only allows this to {@code Archon / Portal}; other
 * applications are refused. Each call blocks until archon-config answers.
 */
public interface ConfigAdmin {

    /**
     * Stores a new configuration, or brings back a deleted one with the same identifier.
     *
     * @param entry the configuration: level, group and application as the level needs them, name, type, value,
     *              read-only flag and description; its id is ignored
     * @param actor who the change is made for, recorded with it
     * @return the configuration as stored, with its id
     * @throws ConfigurationException if one with this identifier exists, a part is invalid, the value does not fit
     *                                its type, or archon-config cannot be reached
     */
    ConfigEntry create(ConfigEntry entry, String actor);

    /**
     * Changes a configuration's type, value, read-only flag and description; its level, group, application and name
     * stay, since they identify it.
     *
     * @param entry the configuration's id and new type, value, read-only flag and description
     * @param actor who the change is made for, recorded with it
     * @return the configuration as stored
     * @throws ConfigurationException if there is no such configuration, the value does not fit its type, or
     *                                archon-config cannot be reached
     */
    ConfigEntry update(ConfigEntry entry, String actor);

    /**
     * Deletes a configuration. Applications that saw it fall back to a less specific one with the same name, if any.
     *
     * @param id    the configuration's id
     * @param actor who the change is made for, recorded with it
     * @throws ConfigurationException if there is no such configuration, or archon-config cannot be reached
     */
    void delete(long id, String actor);
}
