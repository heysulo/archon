package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;

/**
 * Changes any configuration at any level. archon-config only allows this to {@code Archon / Portal}; other
 * applications are refused. Each call blocks until archon-config answers.
 */
public interface ConfigAdmin {

    /**
     * @param actor who the change is made for, recorded with it
     */
    ConfigEntry create(ConfigEntry entry, String actor);

    /** Changes type, value, read-only flag and description; the level, group, application and name stay. */
    ConfigEntry update(ConfigEntry entry, String actor);

    void delete(long id, String actor);
}
