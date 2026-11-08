package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/** A service's definition and running instances, ordered by rank. The definition is null for an unknown service. */
public class ServiceLookupResponseMessage implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String serviceId;
    private final ServiceDefinition definition;
    private final ArrayList<ServiceEndpoint> endpoints;

    public ServiceLookupResponseMessage(long requestId, String serviceId, ServiceDefinition definition,
            List<ServiceEndpoint> endpoints) {
        this.requestId = requestId;
        this.serviceId = serviceId;
        this.definition = definition;
        this.endpoints = new ArrayList<>(endpoints);
    }

    public long getRequestId() {
        return requestId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public boolean isFound() {
        return definition != null;
    }

    public ServiceDefinition getDefinition() {
        return definition;
    }

    public List<ServiceEndpoint> getEndpoints() {
        return List.copyOf(endpoints);
    }
}
