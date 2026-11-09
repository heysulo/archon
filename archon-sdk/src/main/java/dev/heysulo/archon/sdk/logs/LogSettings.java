package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * How many log lines an instance keeps in memory for the portal, and from which level, from configurations like any
 * other: archon-config creates global ones, and a group or application configuration of the same name overrides
 * them for its instances. Instances follow changes as they happen.
 * <ul>
 *     <li>{@value #LINES}: how many lines, {@value #DEFAULT_LINES} unless configured, at most {@value #MAX_LINES}</li>
 *     <li>{@value #LEVEL}: the lowest level kept, TRACE, DEBUG, INFO, WARN or ERROR; {@value #DEFAULT_LEVEL} unless
 *     configured</li>
 * </ul>
 * Lines below the level are not kept, but are still streamed while someone watches.
 */
public final class LogSettings {
    public static final String LINES = "archon.logs.memoryLines";
    public static final String LEVEL = "archon.logs.memoryLevel";
    public static final int DEFAULT_LINES = 1_000;
    public static final int MAX_LINES = 100_000;
    public static final String DEFAULT_LEVEL = "WARN";
    public static final Set<String> NAMES = Set.of(LINES, LEVEL);
    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Logger LOGGER = LoggerFactory.getLogger(LogSettings.class);
    private static String applied;

    private LogSettings() {
    }

    /**
     * Applies the settings.
     *
     * @param values the value of each configuration this instance sees by name, or empty if it sees none
     */
    public static void apply(Function<String, Optional<String>> values) {
        apply(LogBuffer.getInstance(), values);
    }

    static synchronized void apply(LogBuffer buffer, Function<String, Optional<String>> values) {
        int lines = lines(values.apply(LINES).orElse(null));
        String level = level(values.apply(LEVEL).orElse(null));
        buffer.setHistorySize(lines);
        buffer.setMinimumLevel(level);
        String now = lines + " log lines at " + level + " or above";
        if (!now.equals(applied)) {
            applied = now;
            LOGGER.info("Keeping the latest {} in memory for the portal", now);
        }
    }

    /**
     * Applies the settings an instance of {@code group}:{@code application} sees among all configurations, as
     * Archon's own components that hold every configuration do.
     */
    public static void apply(Collection<ConfigEntry> entries, String group, String application) {
        apply(name -> effective(entries, group, application, name));
    }

    /** The value an instance sees: its application's configuration, else its group's, else the global one. */
    static Optional<String> effective(Collection<ConfigEntry> entries, String group, String application,
            String name) {
        return entries.stream()
                .filter(entry -> entry.name().equals(name))
                .filter(entry -> entry.level() == ConfigLevel.GLOBAL
                        || entry.level() == ConfigLevel.GROUP && entry.group().equals(group)
                        || entry.level() == ConfigLevel.APPLICATION && entry.group().equals(group)
                        && entry.application().equals(application))
                .max(Comparator.comparingInt(entry -> entry.level().ordinal()))
                .map(ConfigEntry::value);
    }

    static int lines(String value) {
        if (value == null) {
            return DEFAULT_LINES;
        }
        try {
            long lines = Long.parseLong(value.trim());
            if (lines < 0) {
                throw new NumberFormatException();
            }
            return (int) Math.min(lines, MAX_LINES);
        } catch (NumberFormatException e) {
            LOGGER.warn("{} is {}, not a number of lines. Keeping {}", LINES, value, DEFAULT_LINES);
            return DEFAULT_LINES;
        }
    }

    static String level(String value) {
        if (value == null) {
            return DEFAULT_LEVEL;
        }
        String level = value.trim().toUpperCase(Locale.ROOT);
        if (level.equals("WARNING")) {
            return "WARN";
        }
        if (!LEVELS.contains(level)) {
            LOGGER.warn("{} is {}, not one of TRACE, DEBUG, INFO, WARN or ERROR. Keeping from {}", LEVEL, value,
                    DEFAULT_LEVEL);
            return DEFAULT_LEVEL;
        }
        return level;
    }
}
