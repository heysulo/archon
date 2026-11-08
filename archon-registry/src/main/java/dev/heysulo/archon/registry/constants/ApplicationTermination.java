package dev.heysulo.archon.registry.constants;

public final class ApplicationTermination {
    public static final String REASON_RANK_HELD_BY_ANOTHER_INSTANCE = "Rank is held by another running instance";
    public static final String REASON_RANK_UNAVAILABLE_FOR_RE_REGISTRATION =
            "Provided rank is not available re-registration";

    private ApplicationTermination() {
    }
}
