package dev.heysulo.archon.dictionary.sdk.config;

/**
 * Who can see a configuration. An application sees global configurations, its group's, and its own; where several
 * share a name, the most specific wins: application, then group, then global.
 */
public enum ConfigLevel {
    GLOBAL,
    GROUP,
    APPLICATION;

    /** Higher is more specific, and wins over lower when names clash. */
    public int specificity() {
        return ordinal();
    }
}
