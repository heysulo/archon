package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** Asks the primary registry for a service's definition and where it is running. */
public class ServiceLookupMessage implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String serviceId;

    public ServiceLookupMessage(long requestId, String serviceId) {
        this.requestId = requestId;
        this.serviceId = serviceId;
    }

    public long getRequestId() {
        return requestId;
    }

    public String getServiceId() {
        return serviceId;
    }
}
