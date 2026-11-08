package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;

/**
 * A configuration as an application sees it: the most specific one with its name. Immutable.
 *
 * @param name     the configuration's name, such as {@code ping.intervalMillis}
 * @param type     the type its value has: string, integer, decimal or boolean
 * @param raw      the value in its type's canonical form
 * @param level    where it was set: for the whole system, the application's group, or the application itself
 * @param readOnly whether {@code setConfig} is refused for it
 */
public record ConfigValue(String name, ConfigType type, String raw, ConfigLevel level, boolean readOnly) {

    /**
     * @param entry a stored configuration
     * @return how an application sees it
     */
    public static ConfigValue of(ConfigEntry entry) {
        return new ConfigValue(entry.name(), entry.type(), entry.value(), entry.level(), entry.readOnly());
    }

    /** @return the value as text, whatever its type */
    public String asString() {
        return raw;
    }

    /**
     * @return the value as a whole number
     * @throws NumberFormatException if the value is not a whole number
     */
    public long asLong() {
        return Long.parseLong(raw.trim());
    }

    /**
     * @return the value as an {@code int}
     * @throws NumberFormatException if the value is not a whole number
     * @throws ArithmeticException   if it is a whole number too large for an {@code int}
     */
    public int asInt() {
        return Math.toIntExact(asLong());
    }

    /**
     * @return the value as a number
     * @throws NumberFormatException if the value is not a number
     */
    public double asDouble() {
        return Double.parseDouble(raw.trim());
    }

    /** @return true only for the value {@code true}, ignoring case; false for anything else */
    public boolean asBoolean() {
        return Boolean.parseBoolean(raw.trim());
    }
}
