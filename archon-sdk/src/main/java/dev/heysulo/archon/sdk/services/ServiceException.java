package dev.heysulo.archon.sdk.services;

/** A service could not be looked up, started or stopped. */
public class ServiceException extends RuntimeException {
    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
