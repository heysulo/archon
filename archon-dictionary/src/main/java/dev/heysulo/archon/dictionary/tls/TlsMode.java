package dev.heysulo.archon.dictionary.tls;

import java.util.Locale;

/**
 * Whether connections use TLS, from {@code ARCHON_TLS}. Clusters move from {@link #OFF} through {@link #OPTIONAL}
 * to {@link #REQUIRED} one component at a time.
 */
public enum TlsMode {
    /** Plain connections only. */
    OFF,
    /**
     * Servers accept TLS and plain connections, and do not insist on client certificates, while components are moved
     * across. Archon components and SDK clients connect with TLS.
     */
    OPTIONAL,
    /** TLS only, and every caller but one joining or looking for the primary presents its certificate. */
    REQUIRED;

    public static final String ENV = "ARCHON_TLS";

    public boolean enabled() {
        return this != OFF;
    }

    public static TlsMode fromEnvironment() {
        return parse(System.getenv(ENV));
    }

    /** @throws IllegalArgumentException if the value is not off, optional or required */
    public static TlsMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(ENV + " must be off, optional or required, not " + value, e);
        }
    }
}
