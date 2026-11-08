package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Who sees which configurations, and what a valid configuration looks like. */
final class ConfigRules {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_DESCRIPTION = 512;

    private ConfigRules() {
    }

    /**
     * Checks a new configuration and returns it in canonical form: identifiers trimmed, the parts its level does not
     * use blank, and its value normalized for its type.
     *
     * @throws ConfigException if anything is missing or invalid
     */
    static ConfigEntry validNew(ConfigEntry entry) {
        if (entry == null || entry.level() == null || entry.type() == null) {
            throw new ConfigException("Give the configuration a level and a type");
        }
        String group = entry.level() == ConfigLevel.GLOBAL ? "" : identifier(entry.group(), "group");
        String application = entry.level() == ConfigLevel.APPLICATION ? identifier(entry.application(), "application")
                : "";
        String name = identifier(entry.name(), "name");
        return new ConfigEntry(0, entry.level(), group, application, name, entry.type(), value(entry),
                entry.readOnly(), description(entry.description()), null, null);
    }

    static String value(ConfigEntry entry) {
        try {
            return entry.type().normalize(entry.value());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    static String description(String description) {
        String trimmed = description == null ? "" : description.trim();
        if (trimmed.length() > MAX_DESCRIPTION) {
            throw new ConfigException("Descriptions are at most " + MAX_DESCRIPTION + " characters");
        }
        return trimmed;
    }

    private static String identifier(String value, String part) {
        String trimmed = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new ConfigException("The " + part + " is 1 to 128 letters, digits, dots, dashes or underscores, "
                    + "starting with a letter or digit");
        }
        return trimmed;
    }

    /** The configurations ordered for listing: global first, then by group, application and name. */
    static List<ConfigEntry> ordered(Collection<ConfigEntry> all) {
        return all.stream().sorted((a, b) -> {
            int byLevel = Integer.compare(a.level().specificity(), b.level().specificity());
            if (byLevel != 0) {
                return byLevel;
            }
            int byGroup = a.group().compareTo(b.group());
            if (byGroup != 0) {
                return byGroup;
            }
            int byApplication = a.application().compareTo(b.application());
            return byApplication != 0 ? byApplication : a.name().compareTo(b.name());
        }).toList();
    }
}
