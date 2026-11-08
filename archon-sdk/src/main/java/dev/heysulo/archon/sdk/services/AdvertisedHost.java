package dev.heysulo.archon.sdk.services;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The address clients should use to reach this instance's services. Behind NAT, such as a Kubernetes load
 * balancer, the registry sees a different address, so instances tell it theirs.
 */
public final class AdvertisedHost {
    public static final String ADVERTISED_HOST_ENV = "ARCHON_ADVERTISED_HOST";

    private AdvertisedHost() {
    }

    /** {@value #ADVERTISED_HOST_ENV} if set, else this machine's address, or null to let the registry decide. */
    public static String fromEnvironment() {
        String configured = System.getenv(ADVERTISED_HOST_ENV);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            return local.isLoopbackAddress() ? null : local.getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
