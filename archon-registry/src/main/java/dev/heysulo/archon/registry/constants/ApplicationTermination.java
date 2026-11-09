package dev.heysulo.archon.registry.constants;

public final class ApplicationTermination {
    public static final String REASON_RANK_HELD_BY_ANOTHER_INSTANCE = "Rank is held by another running instance";
    public static final String REASON_RANK_UNAVAILABLE_FOR_RE_REGISTRATION =
            "Provided rank is not available re-registration";

    /** Only defined applications may register; define it in the portal first. */
    public static final String REASON_UNDEFINED_APPLICATION = "Application is not defined";

    /** With TLS required, an instance proves which application it is with its certificate. */
    public static final String REASON_CERTIFICATE_REQUIRED = "A certificate is required. Join with a join token";

    /** The certificate names another application than the one registering. */
    public static final String REASON_CERTIFICATE_MISMATCH = "The certificate is for another application";

    private ApplicationTermination() {
    }
}
