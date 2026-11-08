package dev.heysulo.archon.portal;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;
import dev.heysulo.archon.sdk.ArchonCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * This portal replica's place in the cluster. Every replica registers as {@code Archon / Portal}: the registry ranks
 * them, rank 1 is the primary and the rest are mirrors, and it promotes a mirror when the primary goes. The portal
 * keeps no state, so every replica serves the page whatever its rank.
 */
final class PortalIdentity implements ArchonCallback {
    static final String GROUP = "Archon";
    static final String NAME = "Portal";
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalIdentity.class);

    private final String host = hostName();
    private volatile int rank = RegistryProtocol.RANK_UNKNOWN;

    @Override
    public void onRankUpdateMessage(int newRank) {
        int previous = rank;
        rank = newRank;
        if (previous != newRank) {
            LOGGER.info("--------------- Portal is {} (rank {}) ---------------", role(newRank), newRank);
        }
    }

    String getHost() {
        return host;
    }

    int getRank() {
        return rank;
    }

    String getRole() {
        return role(rank);
    }

    static String role(int rank) {
        if (rank == RegistryProtocol.RANK_PRIMARY) {
            return "primary";
        }
        return rank > RegistryProtocol.RANK_PRIMARY ? "mirror" : "unregistered";
    }

    private static String hostName() {
        String podName = System.getenv("HOSTNAME");
        if (podName != null && !podName.isBlank()) {
            return podName;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
