package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.common.ProtocolSettings;
import dev.heysulo.archon.sdk.tls.ArchonTls;
import dev.heysulo.databridge.core.server.BasicServer;
import dev.heysulo.databridge.core.server.secure.SecureServer;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
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
    private final BooleanSupplier onRegistryThread;
    private final PendingRequests requests = new PendingRequests();
    private final Map<String, RunningService> running = new ConcurrentHashMap<>();

    /** Creates a server for a service. Lets tests avoid binding real ports. */
    @FunctionalInterface
    public interface ServerFactory {
        /**
         * @param port     the port to listen on
         * @param callback handles the server's connections and messages
         * @return a server, not yet listening
         */
        Server create(int port, ServerCallback callback);
    }

    private record RunningService(Server server, int port) {
    }

    /**
     * A plain server with TLS off. With TLS on, a TLS server using this instance's current certificate, which also
     * accepts plain clients until TLS is required.
     */
    static Server newServer(int port, ServerCallback callback) {
        ArchonTls tls = ArchonTls.get();
        if (!tls.enabled()) {
            return new BasicServer(port, callback);
        }
        return new SecureServer(port, callback, tls::serverContext, !tls.required(), null, null);
    }

    /**
     * Applications use this through {@link dev.heysulo.archon.sdk.ArchonInterface}, which creates one.
     *
     * @param group            this instance's application group
     * @param application      this instance's application name
     * @param registry         sends a message to the primary registry
     * @param onRegistryThread whether the calling thread is the registry connection's network thread, on which a
     *                         blocking call would wait forever for an answer that thread must deliver
     */
    public ServiceManager(String group, String application, Consumer<Message> registry,
            BooleanSupplier onRegistryThread) {
        this(group, application, registry, ServicePorts::fromEnvironment, ServiceManager::newServer,
                AdvertisedHost::fromEnvironment, onRegistryThread);
    }

    ServiceManager(String group, String application, Consumer<Message> registry,
            ToIntFunction<ServiceDefinition> portResolver, ServerFactory serverFactory,
            Supplier<String> advertisedHost, BooleanSupplier onRegistryThread) {
        this.advertisedHost = advertisedHost;
        this.onRegistryThread = onRegistryThread;
        this.group = group;
        this.application = application;
        this.registry = registry;
        this.portResolver = portResolver;
        this.serverFactory = serverFactory;
    }

    /**
     * Looks up a service's definition and running instances. Blocks until the registry answers.
     *
     * @param serviceId the service's id
     * @return the definition and the instances serving it, lowest rank first
     * @throws ServiceException      if the service is unknown or the registry does not answer
     * @throws IllegalStateException if called on the registry connection's network thread
     */
    public ServiceInfo lookup(String serviceId) {
        requireBlockingAllowed("lookupService");
        long requestId = requests.open();
        send(requestId, new ServiceLookupMessage(requestId, serviceId));
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
     * @param serviceId the service's id
     * @param callback  handles the service's connections and messages
     * @param configure adjusts the server, for example its trusted packages, before it starts listening
     * @return the started server
     * @throws ServiceException if the service is unknown, belongs to another application definition, is already
     *                          running here, or cannot be started
     */
    public synchronized Server start(String serviceId, ServerCallback callback, Consumer<Server> configure) {
        requireBlockingAllowed("createService");
        if (running.containsKey(serviceId)) {
            throw new ServiceException("Service " + serviceId + " is already running");
        }
        ServiceDefinition definition = lookup(serviceId).definition();
        if (!definition.isOwnedBy(group, application)) {
            throw new ServiceException(String.format("Service %s belongs to %s:%s, not %s:%s", serviceId,
                    definition.group(), definition.application(), group, application));
        }
        int port = portResolver.applyAsInt(definition);
        Server server = serverFactory.create(port, new WarmupAnsweringCallback(callback));
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
            send(requestId, new ServiceStartedMessage(requestId, serviceId, advertisedHost.get(), port)
                    .secure(server instanceof SecureServer));
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
     *
     * @param serviceId the service's id
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
                        service.port()).secure(service.server() instanceof SecureServer)));
    }

    /**
     * Looks up a service and connects to it: to its primary, or to a random instance when load balanced.
     *
     * @param serviceId the service's id
     * @param callback  hears about connects, disconnects and the service's messages
     * @param configure adjusts each connection's settings, for example trusted packages, before it connects
     * @return the connected client
     * @throws ServiceException if the service is unknown, has no instance to connect to, or the connection fails
     */
    public ServiceClient connect(String serviceId, ServiceClientCallback callback,
            Consumer<ProtocolSettings> configure) {
        return connect(serviceId, callback, ServiceClientOptions.defaults().withSettings(configure));
    }

    /**
     * Looks up a service and connects to it with the given options, blocking until connected and warmed up.
     *
     * @param serviceId the service's id
     * @param callback  hears about connects, disconnects and the service's messages
     * @param options   the warm-up and connection settings
     * @return the connected client
     * @throws ServiceException      if the service is unknown, has no instance to connect to, or the connection
     *                               fails
     * @throws IllegalStateException if called on the registry connection's network thread
     */
    public ServiceClient connect(String serviceId, ServiceClientCallback callback, ServiceClientOptions options) {
        requireBlockingAllowed("connectToService");
        ServiceClient client = new ServiceClient(serviceId, this::lookup, callback,
                new DataBridgeServiceConnector(options.getSettings()), options.getWarmupRoundTrips());
        client.connect();
        return client;
    }

    // The registry connection is down while the SDK reconnects; callers see that as a ServiceException like any
    // other unanswered request
    private void send(long requestId, Message message) {
        try {
            registry.accept(message);
        } catch (RuntimeException e) {
            requests.cancel(requestId);
            throw new ServiceException("Cannot reach the registry: " + e.getMessage(), e);
        }
    }

    private void requireBlockingAllowed(String method) {
        if (onRegistryThread.getAsBoolean()) {
            throw new IllegalStateException(method + " waits for the registry, so it cannot run on the registry "
                    + "connection's network thread. Call it from another thread.");
        }
    }

    /**
     * Hands a registry response to the request waiting for it. A refusal of a service announced again after a
     * reconnect, which nobody waits for, stops that service here.
     *
     * @param message a lookup or start response from the registry; others are ignored
     */
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

    /**
     * @param serviceId the service's id
     * @return whether this instance serves it
     */
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
