package dev.heysulo.archon.registry.constants;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public class Constants {
    public static final int REGISTRY_PORT = 8080;
    public static final int REGISTRY_LEADER_ELECTION_SCAN_DELAY = 1_000;
    public static final Instant APPLICATION_START_TIME = Instant.now();
    public static final int RANK_PRIMARY = 1;
    public static final int RANK_MIRROR = 2;
    public static final int RANK_FORECASTED_PRIMARY = 0;
    public static final int RANK_UNKNOWN = -1;

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
