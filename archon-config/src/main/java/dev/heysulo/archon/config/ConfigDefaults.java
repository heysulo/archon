package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.sdk.logs.LogSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Global configurations every instance reads, created at start when missing so they show in the portal with their
 * defaults. A group or application configuration of the same name overrides one. Existing values are never changed;
 * one deleted comes back with its default on the next start.
 */
final class ConfigDefaults {
    static final String ACTOR = "archon-config";
    static final List<ConfigEntry> DEFAULTS = List.of(
            global(LogSettings.LINES, ConfigType.INTEGER, String.valueOf(LogSettings.DEFAULT_LINES),
                    "How many log lines each instance keeps in memory for the portal's live logs, at most "
                            + LogSettings.MAX_LINES + ". 0 keeps none."),
            global(LogSettings.LEVEL, ConfigType.STRING, LogSettings.DEFAULT_LEVEL,
                    "The lowest level of the log lines kept in memory: TRACE, DEBUG, INFO, WARN or ERROR. Lines "
                            + "below it still stream while someone watches."));
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigDefaults.class);

    private ConfigDefaults() {
    }

    private static ConfigEntry global(String name, ConfigType type, String value, String description) {
        // Read only to applications; the portal can still change it
        return new ConfigEntry(0, ConfigLevel.GLOBAL, "", "", name, type, value, true, description, null, null);
    }

    static void ensure(ConfigService service) {
        List<ConfigEntry> existing = service.listAll();
        for (ConfigEntry wanted : DEFAULTS) {
            boolean present = existing.stream().anyMatch(entry -> entry.level() == ConfigLevel.GLOBAL
                    && entry.name().equals(wanted.name()));
            if (present) {
                continue;
            }
            try {
                service.create(wanted, ACTOR);
            } catch (ConfigException e) {
                // Another archon-config replica starting at the same moment created it first
                LOGGER.debug("{} exists already: {}", wanted.name(), e.getMessage());
            }
        }
    }
}
