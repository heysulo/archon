package dev.heysulo.archon.sdk.services;

/** A service could not be looked up, started or stopped. */
public class ServiceException extends RuntimeException {
    /** @param message what failed and why */
    public ServiceException(String message) {
        super(message);
    }

    /**
     * @param message what failed and why
     * @param cause   the underlying failure
     */
    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
