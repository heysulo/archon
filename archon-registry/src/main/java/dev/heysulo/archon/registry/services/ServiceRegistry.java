package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.server.ProcessWatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers service lookups and tracks which application instances are running each service. Running instances are
 * not synced to mirrors: applications announce their services again when they reconnect to a new primary.
 */
public final class ServiceRegistry {
    static final String REASON_UNKNOWN_SERVICE = "Unknown service";
    static final String REASON_NOT_OWNER = "Service belongs to another application definition";
    static final String REASON_INVALID_PORT = "Invalid port";
    private static final int MAX_PORT = 65535;
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceRegistry.class);
    private static ServiceRegistry instance;

    private volatile ServiceCatalog catalog = ServiceCatalog.empty();
    // Service id to the instances running it, keyed by their application record
    private final Map<String, Map<Application, Address>> running = new ConcurrentHashMap<>();

    private record Address(String host, int port, boolean secure) {
    }

    ServiceRegistry() {
    }

    public static synchronized ServiceRegistry getInstance() {
        if (instance == null) {
            instance = new ServiceRegistry();
        }
        return instance;
    }

    public void setCatalog(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    public ServiceCatalog catalog() {
        return catalog;
    }

    public ServiceLookupResponseMessage lookup(ServiceLookupMessage request) {
        Optional<ServiceDefinition> definition = catalog.find(request.getServiceId());
        List<ServiceEndpoint> endpoints = definition.map(this::endpointsOf).orElse(List.of());
        return new ServiceLookupResponseMessage(request.getRequestId(), request.getServiceId(),
                definition.orElse(null), endpoints);
    }

    /** Records a service an application instance started, if that instance's application definition owns it. */
    public ServiceStartResponseMessage start(Application application, ServiceStartedMessage message) {
        String serviceId = message.getServiceId();
        Optional<ServiceDefinition> definition = catalog.find(serviceId);
        String refusal = definition.isEmpty() ? REASON_UNKNOWN_SERVICE
                : !definition.get().isOwnedBy(application.getGroup(), application.getName()) ? REASON_NOT_OWNER
                : message.getPort() <= 0 || message.getPort() > MAX_PORT ? REASON_INVALID_PORT
                : null;
        if (refusal != null) {
            LOGGER.warn("Refused service {} from {}: {}", serviceId, application.getDisplayName(), refusal);
            return ServiceStartResponseMessage.refused(message.getRequestId(), serviceId, refusal);
        }
        String host = message.getHost() == null || message.getHost().isBlank()
                ? application.getConnection().getRemoteAddress()
                : message.getHost();
        running.computeIfAbsent(serviceId, id -> new ConcurrentHashMap<>())
                .put(application, new Address(host, message.getPort(), message.isSecure()));
        LOGGER.info("{} is serving {} at {}:{}", application.getDisplayName(), serviceId, host, message.getPort());
        ProcessWatchers.getInstance().changed();
        return ServiceStartResponseMessage.accepted(message.getRequestId(), serviceId);
    }

    public void stop(Application application, ServiceStoppedMessage message) {
        Map<Application, Address> instances = running.get(message.getServiceId());
        if (instances != null && instances.remove(application) != null) {
            LOGGER.info("{} stopped serving {}", application.getDisplayName(), message.getServiceId());
            ProcessWatchers.getInstance().changed();
        }
    }

    /** The services an application instance serves, as {@code id@host:port}, ordered by id. */
    public List<String> servicesOf(Application application) {
        return running.entrySet().stream()
                .filter(entry -> entry.getValue().containsKey(application))
                .map(entry -> {
                    Address address = entry.getValue().get(application);
                    return address == null ? null : entry.getKey() + "@" + address.host() + ":" + address.port();
                })
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    /** The ids of the services an application instance serves with TLS, ordered by id. */
    public List<String> secureServicesOf(Application application) {
        return running.entrySet().stream()
                .filter(entry -> {
                    Address address = entry.getValue().get(application);
                    return address != null && address.secure();
                })
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Forgets every service an application instance was running, as when it disconnects. */
    public void forget(Application application) {
        running.values().forEach(instances -> instances.remove(application));
        ProcessWatchers.getInstance().changed();
    }

    // Ordered by rank. Of a primary/mirror service, the lowest ranked instance serves, matching app leadership.
    private List<ServiceEndpoint> endpointsOf(ServiceDefinition definition) {
        Map<Application, Address> instances = running.getOrDefault(definition.id(), Map.of());
        List<Map.Entry<Application, Address>> live = instances.entrySet().stream()
                .filter(entry -> entry.getKey().isRunning() && entry.getKey().isConnected())
                .sorted(Comparator.comparingInt(entry -> entry.getKey().getRank()))
                .toList();
        List<ServiceEndpoint> endpoints = new ArrayList<>(live.size());
        for (int i = 0; i < live.size(); i++) {
            Application application = live.get(i).getKey();
            boolean primary = definition.mode() == ServiceMode.PRIMARY_MIRROR && i == 0;
            Address address = live.get(i).getValue();
            endpoints.add(new ServiceEndpoint(address.host(), address.port(), application.getRank(), primary,
                    address.secure()));
        }
        return endpoints;
    }
}
