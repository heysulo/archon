package dev.heysulo.archon.config;

/** Confirms that a caller is the application instance it claims to be. */
interface Verifier {

    enum Result {
        VALID,
        INVALID,
        /** No registry could be asked; the caller should try again shortly. */
        UNAVAILABLE
    }

    Result verify(String group, String name, int rank, String token);
}
