package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * Tells the primary registry that this application instance is serving a service. Sent again after reconnecting,
 * because a newly promoted registry does not know which services were running.
 */
public class ServiceStartedMessage implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String serviceId;
    private final String host;
    private final int port;
    // Absent from instances that predate TLS, which read as false
    private boolean secure;

    /**
     * @param host the address clients should connect to, or null for the registry to use the address it sees this
     *             instance connecting from. Behind NAT, such as a Kubernetes load balancer, that address is wrong.
     */
    public ServiceStartedMessage(long requestId, String serviceId, String host, int port) {
        this.requestId = requestId;
        this.serviceId = serviceId;
        this.host = host;
        this.port = port;
    }

    public long getRequestId() {
        return requestId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** Whether the instance serves the service with TLS. */
    public boolean isSecure() {
        return secure;
    }

    public ServiceStartedMessage secure(boolean value) {
        this.secure = value;
        return this;
    }
}
