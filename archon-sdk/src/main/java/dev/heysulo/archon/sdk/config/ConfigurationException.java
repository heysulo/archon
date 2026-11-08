package dev.heysulo.archon.sdk.config;

/** A configuration could not be read or changed, with archon-config's reason. */
public class ConfigurationException extends RuntimeException {
    private final boolean unavailable;

    public ConfigurationException(String message) {
        this(message, null, false);
    }

    public ConfigurationException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private ConfigurationException(String message, Throwable cause, boolean unavailable) {
        super(message, cause);
        this.unavailable = unavailable;
    }

    /** archon-config could not be reached or did not answer, as opposed to refusing the request. */
    public static ConfigurationException unavailable(String message, Throwable cause) {
        return new ConfigurationException(message, cause, true);
    }

    public boolean isUnavailable() {
        return unavailable;
    }
}
