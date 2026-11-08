package dev.heysulo.archon.registry.constants;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class Constants {
    public static final int REGISTRY_PORT = RegistryProtocol.PORT;
    public static final int REGISTRY_LEADER_ELECTION_SCAN_DELAY = 1_000;
    public static final int REGISTRY_LEADER_ELECTION_MAX_CONNECTION_ATTEMPTS = 30;
    public static final int REGISTRY_FAILOVER_CONNECTION_ATTEMPTS = 1;
    public static final long REGISTRY_LEADER_ELECTION_POLL_MILLIS = 500;
    // A silent peer is dropped after two intervals, which bounds how long a dead primary goes unnoticed
    public static final Duration REGISTRY_HEARTBEAT_INTERVAL = Duration.ofSeconds(3);
    // How often a primary looks for registries it cannot see, including a rival primary after a partition
    public static final Duration REGISTRY_PRIMARY_SCAN_INTERVAL = Duration.ofSeconds(2);
    // How long a primary that lost the vote keeps running, so its standing reaches the winner first
    public static final Duration REGISTRY_LOSER_EXIT_DELAY = Duration.ofSeconds(1);
    // A voter backs its candidate for this long, giving the winner time to register it
    public static final Duration REGISTRY_VOTE_LEASE = Duration.ofSeconds(3);
    public static final long REGISTRY_VOTE_TIMEOUT_MILLIS = 1_000;
    public static final long REGISTRY_VOTE_RETRY_MAX_BACKOFF_MILLIS = 1_000;
    public static final Instant APPLICATION_START_TIME = Instant.now();
    public static final int RANK_PRIMARY = RegistryProtocol.RANK_PRIMARY;
    public static final int RANK_MIRROR = 2;
    public static final int RANK_FORECASTED_PRIMARY = 0;
    public static final int RANK_UNKNOWN = RegistryProtocol.RANK_UNKNOWN;

    // Leader election messages carry an Instant, which Java serializes through java.time.Ser
    public static final List<String> TRUSTED_TIME_CLASSES = List.of("java.time.Instant", "java.time.Ser");

    public static final String APPLICATION_NAME_REGISTRY = "Registry";
    public static final String APPLICATION_GROUP_NAME = "Archon";

    private static final String MY_ADDRESS_ENV = "MY_EXTERNAL_IP";

    private Constants() {
    }

    public static List<String> getRegistryLocations() {
        return RegistryProtocol.getRegistryLocations();
    }

    /** The number of registries, out of every configured one, needed to elect a primary. */
    public static int getQuorum() {
        return getRegistryLocations().size() / 2 + 1;
    }

    public static String getMyAddress() {
        String myAddress = System.getenv(MY_ADDRESS_ENV);
        if (myAddress == null) {
            throw new IllegalStateException(MY_ADDRESS_ENV + " environment variable not set");
        }
        return myAddress;
    }
}
