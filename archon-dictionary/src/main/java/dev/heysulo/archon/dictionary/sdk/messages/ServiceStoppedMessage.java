package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** Tells the primary registry that this application instance no longer serves a service. */
public class ServiceStoppedMessage implements Message {
    private static final long serialVersionUID = 1L;

    private final String serviceId;

    public ServiceStoppedMessage(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceId() {
        return serviceId;
    }
}
