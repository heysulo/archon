package dev.heysulo.archon.portal;

/** The portal cannot get an answer from the registries. Shown to the user as is. */
final class PortalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PortalException(String message) {
        super(message);
    }
}
