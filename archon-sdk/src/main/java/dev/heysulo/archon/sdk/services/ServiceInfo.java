package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;

import java.util.List;
import java.util.Optional;

/**
 * What the registry knows about a service: its definition, and its running instances ordered by rank.
 *
 * @param definition the service as defined in the portal: id, owner, port offset and mode
 * @param endpoints  the instances serving it, lowest rank first; of a primary/mirror service, the serving one is
 *                   marked {@linkplain ServiceEndpoint#primary() primary}
 */
public record ServiceInfo(ServiceDefinition definition, List<ServiceEndpoint> endpoints) {

    /**
     * Copies {@code endpoints}, so the record cannot change.
     *
     * @param definition the service as defined in the portal
     * @param endpoints  the instances serving it, lowest rank first
     */
    public ServiceInfo {
        endpoints = List.copyOf(endpoints);
    }

    /** @return how clients use the instances: one serving at a time, or spread across all */
    public ServiceMode mode() {
        return definition.mode();
    }

    /**
     * The serving instance of a primary/mirror service.
     *
     * @return it, if one is running; always empty when load balancing
     */
    public Optional<ServiceEndpoint> primary() {
        return endpoints.stream().filter(ServiceEndpoint::primary).findFirst();
    }

    /** @return whether any instance serves the service */
    public boolean isRunning() {
        return !endpoints.isEmpty();
    }
}
