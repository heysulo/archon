package dev.heysulo.archon.sdk.config;

/**
 * A configuration could not be read or changed, with archon-config's reason. {@link #isUnavailable()} tells a
 * refusal, which retrying will not change, from archon-config being out of reach, which may pass.
 */
public class ConfigurationException extends RuntimeException {
    /** Whether archon-config was out of reach, rather than refusing. */
    private final boolean unavailable;

    /** @param message archon-config's reason for refusing */
    public ConfigurationException(String message) {
        this(message, null, false);
    }

    /**
     * @param message archon-config's reason for refusing
     * @param cause   the underlying failure
     */
    public ConfigurationException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private ConfigurationException(String message, Throwable cause, boolean unavailable) {
        super(message, cause);
        this.unavailable = unavailable;
    }

    /**
     * archon-config could not be reached or did not answer, as opposed to refusing the request.
     *
     * @param message what could not be reached
     * @param cause   the underlying failure, or null
     * @return the exception, {@linkplain #isUnavailable() unavailable}
     */
    public static ConfigurationException unavailable(String message, Throwable cause) {
        return new ConfigurationException(message, cause, true);
    }

    /** @return true if archon-config was out of reach, false if it refused */
    public boolean isUnavailable() {
        return unavailable;
    }
}
