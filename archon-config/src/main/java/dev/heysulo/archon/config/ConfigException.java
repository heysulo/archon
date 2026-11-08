package dev.heysulo.archon.config;

/** A refused configuration request, with a message safe to show the caller. */
final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConfigException(String message) {
        super(message);
    }
}
