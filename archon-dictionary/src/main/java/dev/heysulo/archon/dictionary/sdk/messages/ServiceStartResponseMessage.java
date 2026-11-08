package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** Whether the registry accepted a started service. A refused service must be stopped. */
public class ServiceStartResponseMessage implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String serviceId;
    private final boolean accepted;
    private final String reason;

    public ServiceStartResponseMessage(long requestId, String serviceId, boolean accepted, String reason) {
        this.requestId = requestId;
        this.serviceId = serviceId;
        this.accepted = accepted;
        this.reason = reason;
    }

    public static ServiceStartResponseMessage accepted(long requestId, String serviceId) {
        return new ServiceStartResponseMessage(requestId, serviceId, true, null);
    }

    public static ServiceStartResponseMessage refused(long requestId, String serviceId, String reason) {
        return new ServiceStartResponseMessage(requestId, serviceId, false, reason);
    }

    public long getRequestId() {
        return requestId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    /** Why the service was refused, or null if it was accepted. */
    public String getReason() {
        return reason;
    }
}
