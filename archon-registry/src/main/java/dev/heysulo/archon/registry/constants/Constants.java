package dev.heysulo.archon.registry.constants;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public class Constants {
    public static final int REGISTRY_PORT = 8080;
    public static final int REGISTRY_LEADER_ELECTION_SCAN_DELAY = 1_000;
    public static final int REGISTRY_LEADER_ELECTION_MAX_CONNECTION_ATTEMPTS = 30;
    public static final int REGISTRY_FAILOVER_CONNECTION_ATTEMPTS = 1;
    public static final long REGISTRY_LEADER_ELECTION_POLL_MILLIS = 500;
    // A silent peer is dropped after two intervals, which bounds how long a dead primary goes unnoticed
    public static final Duration REGISTRY_HEARTBEAT_INTERVAL = Duration.ofSeconds(3);
    public static final Instant APPLICATION_START_TIME = Instant.now();
    public static final int RANK_PRIMARY = 1;
    public static final int RANK_MIRROR = 2;
    public static final int RANK_FORECASTED_PRIMARY = 0;
    public static final int RANK_UNKNOWN = -1;

    // Leader election messages carry an Instant, which Java serializes through java.time.Ser
    public static final List<String> TRUSTED_TIME_CLASSES = List.of("java.time.Instant", "java.time.Ser");

    public static final String APPLICATION_NAME_REGISTRY = "Registry";
    public static final String APPLICATION_GROUP_NAME = "Archon";

    public static List<String> getRegistryLocations() {
        String hosts = System.getenv("ARCHON_REGISTRY_LOCATIONS");
        if (hosts == null) {
            return List.of();
        }
        return Arrays.asList(hosts.split("\\|"));
    }

    public static String getMyAddress() {
        String myAddress =  System.getenv("MY_EXTERNAL_IP");
        if (myAddress == null) {
            throw new RuntimeException("MY_EXTERNAL_IP environment variable not set");
        }
        return myAddress;
    }
}
