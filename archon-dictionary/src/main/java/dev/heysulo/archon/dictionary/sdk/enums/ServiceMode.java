package dev.heysulo.archon.dictionary.sdk.enums;

/** How the running instances of a service share its work. */
public enum ServiceMode {
    /** One instance serves, the one whose application holds the lowest rank. The rest stand by. */
    PRIMARY_MIRROR,
    /** Every running instance serves, and clients spread their calls across them. */
    LOAD_BALANCE
}
