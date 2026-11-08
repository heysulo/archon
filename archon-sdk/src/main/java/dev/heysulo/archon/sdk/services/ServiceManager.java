package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.common.ProtocolSettings;
import dev.heysulo.databridge.core.server.BasicServer;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Starts, stops and looks up services through the primary registry. Its blocking calls wait for the registry's
 * answer, so they must not run on the registry connection's event loop.
 */
public final class ServiceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceManager.class);
    private static final long RESPONSE_TIMEOUT_MILLIS = 5_000;

    private final String group;
    private final String application;
    private final Consumer<Message> registry;
    private final ToIntFunction<ServiceDefinition> portResolver;
    private final ServerFactory serverFactory;
    private final Supplier<String> advertisedHost;
    private final PendingRequests requests = new PendingRequests();
    private final Map<String, RunningService> running = new ConcurrentHashMap<>();

    /** Creates a server for a service. Lets tests avoid binding real ports. */
    @FunctionalInterface
    public interface ServerFactory {
        Server create(int port, ServerCallback callback);
    }

    private record RunningService(Server server, int port) {
    }

    /**
     * @param registry sends a message to the primary registry
     */
    public ServiceManager(String group, String application, Consumer<Message> registry) {
        this(group, application, registry, ServicePorts::fromEnvironment, BasicServer::new,
                AdvertisedHost::fromEnvironment);
    }

    ServiceManager(String group, String application, Consumer<Message> registry,
            ToIntFunction<ServiceDefinition> portResolver, ServerFactory serverFactory,
            Supplier<String> advertisedHost) {
        this.advertisedHost = advertisedHost;
        this.group = group;
        this.application = application;
        this.registry = registry;
        this.portResolver = portResolver;
        this.serverFactory = serverFactory;
    }

    /**
     * @throws ServiceException if the service is unknown or the registry does not answer
     */
    public ServiceInfo lookup(String serviceId) {
        long requestId = requests.open();
        registry.accept(new ServiceLookupMessage(requestId, serviceId));
        ServiceLookupResponseMessage response =
                requests.await(requestId, ServiceLookupResponseMessage.class, RESPONSE_TIMEOUT_MILLIS);
        if (!response.isFound()) {
            throw new ServiceException("Unknown service " + serviceId);
        }
        return new ServiceInfo(response.getDefinition(), response.getEndpoints());
    }

    /**
     * Starts serving a service on this instance's base port plus the service's offset, then announces it.
     *
     * @param configure adjusts the server, for example its trusted packages, before it starts listening
     * @return the started server
     * @throws ServiceException if the service is unknown, belongs to another application definition, is already
     *                          running here, or cannot be started
     */
    public synchronized Server start(String serviceId, ServerCallback callback, Consumer<Server> configure) {
        if (running.containsKey(serviceId)) {
            throw new ServiceException("Service " + serviceId + " is already running");
        }
        ServiceDefinition definition = lookup(serviceId).definition();
        if (!definition.isOwnedBy(group, application)) {
            throw new ServiceException(String.format("Service %s belongs to %s:%s, not %s:%s", serviceId,
                    definition.group(), definition.application(), group, application));
        }
        int port = portResolver.applyAsInt(definition);
        Server server = serverFactory.create(port, callback);
        configure.accept(server);
        try {
            server.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Interrupted while starting service " + serviceId, e);
        } catch (RuntimeException e) {
            throw new ServiceException("Cannot start service " + serviceId + " on port " + port, e);
        }
        announce(serviceId, server, port);
        running.put(serviceId, new RunningService(server, port));
        LOGGER.info("Serving {} on port {}", serviceId, port);
        return server;
    }

    private void announce(String serviceId, Server server, int port) {
        long requestId = requests.open();
        ServiceStartResponseMessage response;
        try {
            registry.accept(new ServiceStartedMessage(requestId, serviceId, advertisedHost.get(), port));
            response = requests.await(requestId, ServiceStartResponseMessage.class, RESPONSE_TIMEOUT_MILLIS);
        } catch (RuntimeException e) {
            stopQuietly(server);
            throw e;
        }
        if (!response.isAccepted()) {
            stopQuietly(server);
            throw new ServiceException("The registry refused service " + serviceId + ": " + response.getReason());
        }
    }

    /**
     * Tells the registry, then stops serving a service. Does nothing if it is not running here. Telling the registry
     * first keeps lookups from returning an instance that is shutting down.
     */
    public synchronized void stop(String serviceId) {
        RunningService service = running.remove(serviceId);
        if (service == null) {
            return;
        }
        try {
            registry.accept(new ServiceStoppedMessage(serviceId));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not tell the registry that {} stopped: {}", serviceId, e.getMessage());
        }
        stopQuietly(service.server());
        LOGGER.info("Stopped serving {}", serviceId);
    }

    /** Announces every running service again, as a newly promoted registry does not know about them. */
    public void announceAll() {
        running.forEach((serviceId, service) ->
                registry.accept(new ServiceStartedMessage(PendingRequests.UNTRACKED, serviceId, advertisedHost.get(),
                        service.port())));
    }

    /**
     * Looks up a service and connects to it: to its primary, or to a random instance when load balanced.
     *
     * @param configure adjusts each connection's settings, for example trusted packages, before it connects
     * @throws ServiceException if the service is unknown, has no instance to connect to, or the connection fails
     */
    public ServiceClient connect(String serviceId, ServiceClientCallback callback,
            Consumer<ProtocolSettings> configure) {
        ServiceClient client = new ServiceClient(serviceId, this::lookup, callback,
                new DataBridgeServiceConnector(configure));
        client.connect();
        return client;
    }

    /** Hands a registry response to the request waiting for it. */
    public void onResponse(Message message) {
        if (message instanceof ServiceLookupResponseMessage lookup) {
            requests.complete(lookup.getRequestId(), lookup);
        } else if (message instanceof ServiceStartResponseMessage start
                && !requests.complete(start.getRequestId(), start) && !start.isAccepted()) {
            // Nobody waits on a service announced again after reconnecting, so a refusal stops it here
            LOGGER.error("The registry refused running service {}: {}. Stopping it", start.getServiceId(),
                    start.getReason());
            RunningService service = running.remove(start.getServiceId());
            if (service != null) {
                stopQuietly(service.server());
            }
        }
    }

    public boolean isRunning(String serviceId) {
        return running.containsKey(serviceId);
    }

    private static void stopQuietly(Server server) {
        try {
            server.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to stop service server", e);
        }
    }
}
