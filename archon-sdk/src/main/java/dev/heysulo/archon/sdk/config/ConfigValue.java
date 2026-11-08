package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;

/**
 * A configuration as an application sees it: the most specific one with its name.
 *
 * @param raw      the value in its type's canonical form
 * @param level    where it was set: for the whole system, the application's group, or the application itself
 * @param readOnly whether {@code setConfig} is refused for it
 */
public record ConfigValue(String name, ConfigType type, String raw, ConfigLevel level, boolean readOnly) {

    public static ConfigValue of(ConfigEntry entry) {
        return new ConfigValue(entry.name(), entry.type(), entry.value(), entry.level(), entry.readOnly());
    }

    public String asString() {
        return raw;
    }

    /**
     * @throws NumberFormatException if the value is not a whole number
     */
    public long asLong() {
        return Long.parseLong(raw.trim());
    }

    /**
     * @throws NumberFormatException if the value is not a whole number that fits an int
     */
    public int asInt() {
        return Math.toIntExact(asLong());
    }

    /**
     * @throws NumberFormatException if the value is not a number
     */
    public double asDouble() {
        return Double.parseDouble(raw.trim());
    }

    /** True only for the value {@code true}, ignoring case. */
    public boolean asBoolean() {
        return Boolean.parseBoolean(raw.trim());
    }
}
