package dev.heysulo.archon.dictionary.sdk.services;

import java.io.Serial;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Where an instance runs, as it reports itself when it registers.
 *
 * @param host    the host name; in Kubernetes, the pod name
 * @param address the address others reach it at; in Kubernetes, the pod IP, or a registry's load balancer address
 * @param node    the machine it runs on, or null when unknown; in Kubernetes, the node name
 */
public record InstanceDetails(String host, String address, String node) implements Serializable {
    public static final String NODE_ENV = "ARCHON_NODE_NAME";
    public static final String ADVERTISED_HOST_ENV = "ARCHON_ADVERTISED_HOST";
    @Serial
    private static final long serialVersionUID = 1L;

    /** This instance's details, with its address from {@value #ADVERTISED_HOST_ENV} or the local address. */
    public static InstanceDetails fromEnvironment() {
        return fromEnvironment(advertisedAddress());
    }

    /** This instance's details with a known address, such as a registry's load balancer address. */
    public static InstanceDetails fromEnvironment(String address) {
        return new InstanceDetails(hostName(), address, blankToNull(System.getenv(NODE_ENV)));
    }

    /** These details, with the given address in place of a missing one. */
    public InstanceDetails withAddressOr(String fallback) {
        return address == null || address.isBlank() ? new InstanceDetails(host, fallback, node) : this;
    }

    private static String hostName() {
        String configured = blankToNull(System.getenv("HOSTNAME"));
        if (configured != null) {
            return configured;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String advertisedAddress() {
        String configured = blankToNull(System.getenv(ADVERTISED_HOST_ENV));
        if (configured != null) {
            return configured;
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            return local.isLoopbackAddress() ? null : local.getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
