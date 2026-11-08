package dev.heysulo.archon.portal.auth;

/** A refused authentication or user management request, with the HTTP status and a message safe to show. */
public final class AuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    static AuthException badRequest(String message) {
        return new AuthException(400, message);
    }

    static AuthException unauthorized(String message) {
        return new AuthException(401, message);
    }

    static AuthException forbidden(String message) {
        return new AuthException(403, message);
    }

    static AuthException notFound(String message) {
        return new AuthException(404, message);
    }

    static AuthException conflict(String message) {
        return new AuthException(409, message);
    }

    static AuthException tooManyRequests(String message) {
        return new AuthException(429, message);
    }
}
