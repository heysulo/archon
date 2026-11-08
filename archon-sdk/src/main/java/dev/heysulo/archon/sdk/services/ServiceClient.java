package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * A connection to a service, found through the registry. Connects to the primary of a primary/mirror service, or
 * to a random instance of a load balanced one. When the connection drops it looks the service up again and
 * reconnects, so it follows the service to a new primary.
 */
public final class ServiceClient implements AutoCloseable {
    static final long RECONNECT_DELAY_MILLIS = 1_000;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClient.class);
    private static final ScheduledExecutorService DEFAULT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("archon-service-reconnect", true));

    private final String serviceId;
    private final Function<String, ServiceInfo> lookup;
    private final ServiceClientCallback callback;
    private final ServiceConnection.Connector connector;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final Object attemptLock = new Object();
    private volatile Attempt active;
    private volatile boolean closed;

    ServiceClient(String serviceId, Function<String, ServiceInfo> lookup, ServiceClientCallback callback,
            ServiceConnection.Connector connector) {
        this(serviceId, lookup, callback, connector, DEFAULT_SCHEDULER);
    }

    ServiceClient(String serviceId, Function<String, ServiceInfo> lookup, ServiceClientCallback callback,
            ServiceConnection.Connector connector, ScheduledExecutorService scheduler) {
        this.serviceId = serviceId;
        this.lookup = lookup;
        this.callback = callback;
        this.connector = connector;
        this.scheduler = scheduler;
    }

    /**
     * Connects for the first time, blocking until the connection is ready.
     *
     * @throws ServiceException if the service is unknown, has no instance to connect to, or the connection fails.
     *                          A client that failed to connect does not retry.
     */
    void connect() {
        attempt();
    }

    public String getServiceId() {
        return serviceId;
    }

    /** The instance this client is connected to, if it is connected. */
    public Optional<ServiceEndpoint> getEndpoint() {
        Attempt attempt = active;
        return attempt != null && attempt.isReady() ? Optional.of(attempt.endpoint) : Optional.empty();
    }

    public boolean isConnected() {
        return getEndpoint().isPresent();
    }

    /**
     * @throws ServiceException if not connected, for example while reconnecting
     */
    public void send(Message message) {
        Attempt attempt = active;
        if (attempt == null || !attempt.isReady()) {
            throw new ServiceException("Not connected to service " + serviceId);
        }
        attempt.connection.send(message);
    }

    /** Disconnects and stops reconnecting. */
    @Override
    public void close() {
        closed = true;
        Attempt attempt = active;
        if (attempt != null) {
            attempt.connection.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void attempt() {
        synchronized (attemptLock) {
            if (closed) {
                throw new ServiceException("Client for service " + serviceId + " is closed");
            }
            ServiceEndpoint endpoint = choose(lookup.apply(serviceId));
            Attempt attempt = new Attempt(endpoint);
            // Set before connecting, so the handshake is not mistaken for a stale connection's
            active = attempt;
            attempt.connection = connector.open(endpoint, attempt);
            try {
                attempt.connection.connect();
                if (!attempt.handshake.await(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw new ServiceException(String.format("Service %s at %s:%d did not complete the handshake",
                            serviceId, endpoint.host(), endpoint.port()));
                }
                if (attempt.disconnected) {
                    throw new ServiceException(String.format("Service %s at %s:%d closed the connection", serviceId,
                            endpoint.host(), endpoint.port()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                attempt.connection.close();
                throw new ServiceException("Interrupted while connecting to service " + serviceId, e);
            } catch (ServiceException e) {
                attempt.connection.close();
                throw e;
            } catch (Exception e) {
                // Netty rethrows checked connect failures, such as a refused connection, without declaring them
                attempt.connection.close();
                throw new ServiceException(String.format("Cannot connect to service %s at %s:%d", serviceId,
                        endpoint.host(), endpoint.port()), e);
            }
            if (closed) {
                attempt.connection.close();
            }
        }
    }

    /**
     * @throws ServiceException if no instance is available
     */
    static ServiceEndpoint choose(ServiceInfo info) {
        String serviceId = info.definition().id();
        List<ServiceEndpoint> endpoints = info.endpoints();
        if (endpoints.isEmpty()) {
            throw new ServiceException("Service " + serviceId + " has no running instance");
        }
        if (info.mode() == ServiceMode.PRIMARY_MIRROR) {
            return info.primary().orElseThrow(() -> new ServiceException("Service " + serviceId + " has no primary"));
        }
        return endpoints.get(ThreadLocalRandom.current().nextInt(endpoints.size()));
    }

    private void scheduleReconnect() {
        if (!closed && reconnecting.compareAndSet(false, true)) {
            scheduler.schedule(this::reconnect, RECONNECT_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    // Runs on the scheduler, never on a network thread, because the lookup blocks on the registry
    private void reconnect() {
        if (closed) {
            reconnecting.set(false);
            return;
        }
        try {
            attempt();
            reconnecting.set(false);
            LOGGER.info("Reconnected to service {}", serviceId);
        } catch (ServiceException e) {
            LOGGER.info("Reconnecting to service {} failed: {}. Retrying in {} ms", serviceId, e.getMessage(),
                    RECONNECT_DELAY_MILLIS);
            if (closed) {
                reconnecting.set(false);
            } else {
                scheduler.schedule(this::reconnect, RECONNECT_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** One connection attempt. Events from an attempt that has been replaced are ignored. */
    private final class Attempt implements ServiceConnection.Listener {
        private final ServiceEndpoint endpoint;
        private final CountDownLatch handshake = new CountDownLatch(1);
        private volatile ServiceConnection connection;
        private volatile boolean disconnected;

        Attempt(ServiceEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        boolean isReady() {
            return handshake.getCount() == 0 && !disconnected && connection != null && connection.isConnected();
        }

        private boolean isActive() {
            return active == this;
        }

        @Override
        public void onConnect() {
            handshake.countDown();
            if (isActive()) {
                LOGGER.info("Connected to service {} at {}:{}", serviceId, endpoint.host(), endpoint.port());
                callback.onConnect(ServiceClient.this, endpoint);
            }
        }

        @Override
        public void onDisconnect() {
            boolean wasReady = handshake.getCount() == 0 && !disconnected;
            disconnected = true;
            // Releases a connect still waiting for the handshake
            handshake.countDown();
            if (!isActive() || closed) {
                return;
            }
            if (wasReady) {
                LOGGER.warn("Lost service {} at {}:{}", serviceId, endpoint.host(), endpoint.port());
                callback.onDisconnect(ServiceClient.this, endpoint);
                scheduleReconnect();
            }
        }

        @Override
        public void onMessage(Message message) {
            if (isActive()) {
                callback.onMessage(ServiceClient.this, message);
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (isActive()) {
                callback.onError(ServiceClient.this, cause);
            }
        }
    }
}
