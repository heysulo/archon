package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.heysulo.archon.dictionary.sdk.messages.ServiceWarmupMessage;

import java.util.Arrays;
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
 * A connection to a service, found through the registry, from
 * {@link dev.heysulo.archon.sdk.ArchonInterface#connectToService(String, ServiceClientCallback)}. Connects to the
 * serving instance of a primary/mirror service, or to a random instance of a load balanced one. When the connection
 * drops it looks the service up again and reconnects, every second until it succeeds, so it follows the service to
 * a new primary. Its callback hears about each disconnect and connect. Thread safe.
 */
public final class ServiceClient implements AutoCloseable {
    static final long RECONNECT_DELAY_MILLIS = 1_000;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    // A warm-up that has not finished by then is abandoned, and the connection is used as it is
    private static final long WARMUP_TIMEOUT_MILLIS = 30_000;
    private static final int WARMUP_SAMPLE = 1_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClient.class);
    private static final ScheduledExecutorService DEFAULT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("archon-service-reconnect", true));

    private final String serviceId;
    private final Function<String, ServiceInfo> lookup;
    private final ServiceClientCallback callback;
    private final ServiceConnection.Connector connector;
    private final ScheduledExecutorService scheduler;
    private final int warmupRoundTrips;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final Object attemptLock = new Object();
    private volatile Attempt active;
    private volatile boolean closed;

    ServiceClient(String serviceId, Function<String, ServiceInfo> lookup, ServiceClientCallback callback,
            ServiceConnection.Connector connector, int warmupRoundTrips) {
        this(serviceId, lookup, callback, connector, DEFAULT_SCHEDULER, warmupRoundTrips);
    }

    ServiceClient(String serviceId, Function<String, ServiceInfo> lookup, ServiceClientCallback callback,
            ServiceConnection.Connector connector, ScheduledExecutorService scheduler) {
        this(serviceId, lookup, callback, connector, scheduler, 0);
    }

    ServiceClient(String serviceId, Function<String, ServiceInfo> lookup, ServiceClientCallback callback,
            ServiceConnection.Connector connector, ScheduledExecutorService scheduler, int warmupRoundTrips) {
        this.warmupRoundTrips = warmupRoundTrips;
        this.serviceId = serviceId;
        this.lookup = lookup;
        this.callback = callback;
        this.connector = connector;
        this.scheduler = scheduler;
    }

    /**
     * Connects for the first time, blocking until the connection is ready and warmed up.
     *
     * @throws ServiceException if the service is unknown, has no instance to connect to, or the connection fails.
     *                          A client that failed to connect does not retry.
     */
    void connect() {
        attempt();
    }

    /** @return the id of the service this client connects to */
    public String getServiceId() {
        return serviceId;
    }

    /**
     * The instance this client is connected to.
     *
     * @return it, while connected and warmed up; empty while connecting or reconnecting, and once closed
     */
    public Optional<ServiceEndpoint> getEndpoint() {
        Attempt attempt = active;
        return attempt != null && attempt.isReady() ? Optional.of(attempt.endpoint) : Optional.empty();
    }

    /** @return whether messages can be sent now: connected and warmed up */
    public boolean isConnected() {
        return getEndpoint().isPresent();
    }

    /**
     * Sends a message to the instance this client is connected to. Does not wait for it to be written, or answered;
     * answers arrive through the callback.
     *
     * @param message the message; its classes must be trusted by the service, see
     *                {@link ServiceClientOptions#withSettings}
     * @throws ServiceException if not connected, for example while reconnecting
     */
    public void send(Message message) {
        Attempt attempt = active;
        if (attempt == null || !attempt.isReady()) {
            throw new ServiceException("Not connected to service " + serviceId);
        }
        attempt.connection.send(message);
    }

    /** Disconnects and stops reconnecting, for good. Safe to call more than once. */
    @Override
    public void close() {
        closed = true;
        Attempt attempt = active;
        if (attempt != null) {
            attempt.connection.close();
        }
    }

    /** @return whether {@link #close()} was called */
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
                if (!attempt.ready.await(WARMUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn("Warm-up of service {} at {}:{} did not finish in {} ms. Using the connection as it is",
                            serviceId, endpoint.host(), endpoint.port(), WARMUP_TIMEOUT_MILLIS);
                    attempt.becomeReady();
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

    /**
     * One connection attempt: handshake, then the warm-up, then ready. Events from an attempt that has been replaced
     * are ignored.
     */
    private final class Attempt implements ServiceConnection.Listener {
        private final ServiceEndpoint endpoint;
        private final CountDownLatch handshake = new CountDownLatch(1);
        private final CountDownLatch ready = new CountDownLatch(1);
        private final AtomicBoolean becameReady = new AtomicBoolean(false);
        private final long[] warmupSample = new long[WARMUP_SAMPLE];
        private volatile ServiceConnection connection;
        private volatile boolean disconnected;
        private long warmupStartedNanos;

        Attempt(ServiceEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        boolean isReady() {
            return becameReady.get() && !disconnected && connection != null && connection.isConnected();
        }

        private boolean isActive() {
            return active == this;
        }

        @Override
        public void onConnect() {
            handshake.countDown();
            if (warmupRoundTrips <= 0) {
                becomeReady();
                return;
            }
            warmupStartedNanos = System.nanoTime();
            sendWarmup(1);
        }

        // Each round trip goes out on the network thread the moment the previous answer arrives
        private void continueWarmup(ServiceWarmupMessage answer) {
            long done = answer.getSequence();
            warmupSample[(int) (done % WARMUP_SAMPLE)] = System.nanoTime() - answer.getSentAtNanos();
            if (done < warmupRoundTrips) {
                sendWarmup(done + 1);
                return;
            }
            long[] sample = Arrays.copyOf(warmupSample, (int) Math.min(done, WARMUP_SAMPLE));
            Arrays.sort(sample);
            LOGGER.info("Warmed up service {} at {}:{} with {} round trips in {} ms. Last {}: median {} us",
                    serviceId, endpoint.host(), endpoint.port(), warmupRoundTrips,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmupStartedNanos), sample.length,
                    TimeUnit.NANOSECONDS.toMicros(sample[sample.length / 2]));
            becomeReady();
        }

        private void sendWarmup(long sequence) {
            try {
                connection.send(new ServiceWarmupMessage(sequence, System.nanoTime(), false));
            } catch (RuntimeException e) {
                // The connection dropped; onDisconnect releases the waiting connect
                LOGGER.debug("Warm-up of service {} stopped: {}", serviceId, e.getMessage());
            }
        }

        /** Marks the connection usable and tells the application, once. */
        void becomeReady() {
            if (disconnected || !becameReady.compareAndSet(false, true)) {
                return;
            }
            ready.countDown();
            if (isActive()) {
                LOGGER.info("Connected to service {} at {}:{}", serviceId, endpoint.host(), endpoint.port());
                callback.onConnect(ServiceClient.this, endpoint);
            }
        }

        @Override
        public void onDisconnect() {
            boolean wasReady = becameReady.get() && !disconnected;
            disconnected = true;
            // Releases a connect still waiting for the handshake or the warm-up
            handshake.countDown();
            ready.countDown();
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
            if (message instanceof ServiceWarmupMessage warmup) {
                if (warmup.isReply() && !becameReady.get()) {
                    continueWarmup(warmup);
                }
                return;
            }
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
