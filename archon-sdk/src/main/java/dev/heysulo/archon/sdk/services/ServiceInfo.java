package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;

import java.util.List;
import java.util.Optional;

/**
 * What the registry knows about a service: its definition, and its running instances ordered by rank.
 */
public record ServiceInfo(ServiceDefinition definition, List<ServiceEndpoint> endpoints) {

    public ServiceInfo {
        endpoints = List.copyOf(endpoints);
    }

    public ServiceMode mode() {
        return definition.mode();
    }

    /** The serving instance of a primary/mirror service, if one is running. Always empty when load balancing. */
    public Optional<ServiceEndpoint> primary() {
        return endpoints.stream().filter(ServiceEndpoint::primary).findFirst();
    }

    public boolean isRunning() {
        return !endpoints.isEmpty();
    }
}
