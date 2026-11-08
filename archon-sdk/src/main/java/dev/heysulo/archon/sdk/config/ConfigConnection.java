package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHello;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHelloResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigResponse;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.archon.sdk.services.PendingRequests;
import dev.heysulo.archon.sdk.services.ServiceClient;
import dev.heysulo.archon.sdk.services.ServiceClientCallback;
import dev.heysulo.archon.sdk.services.ServiceException;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A connection to archon-config that keeps itself up: it connects in the background, says who this instance is
 * after every connect, starts over when refused, and sends requests once accepted. Subclasses say what to ask for
 * and handle what arrives. Not meant to be extended outside the SDK.
 */
public abstract class ConfigConnection implements ServiceClientCallback {
    static final String SERVICE = "archon-config";
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigConnection.class);
    private static final long RESPONSE_TIMEOUT_MILLIS = 5_000;
    // No archon-config defined: check now and then, so starting one later needs no application restart
    private static final long UNKNOWN_SERVICE_RETRY_MILLIS = 30_000;
    private static final long RETRY_MILLIS = 2_000;

    /** Opens a connection to archon-config. */
    @FunctionalInterface
    public interface Connector {
        /**
         * @param serviceId archon-config's service id
         * @param callback  hears about the connection
         * @return the connected client
         * @throws dev.heysulo.archon.sdk.services.ServiceException if archon-config cannot be reached yet
         */
        ServiceClient connect(String serviceId, ServiceClientCallback callback);
    }

    /** This instance's application group, sent in every hello. */
    protected final String group;
    /** This instance's application name, sent in every hello. */
    protected final String name;
    private final IntSupplier rank;
    private final Supplier<String> token;
    private final Connector connector;
    private final ScheduledExecutorService scheduler;
    private final PendingRequests requests = new PendingRequests();
    private volatile ServiceClient client;
    private volatile boolean accepted;
    private volatile boolean reportedWaiting;
    private volatile boolean closed;

    ConfigConnection(String group, String name, IntSupplier rank, Supplier<String> token, Connector connector,
            ScheduledExecutorService scheduler) {
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.token = token;
        this.connector = connector;
        this.scheduler = scheduler;
    }

    /**
     * Starts connecting in the background, then keeps the connection up: every 2 seconds while archon-config is
     * unreachable, every 30 while it is not defined at all. Returns at once.
     */
    public void start() {
        scheduler.execute(this::connect);
    }

    /** Disconnects for good, and stops reconnecting. */
    public void close() {
        closed = true;
        ServiceClient current = client;
        if (current != null) {
            current.close();
        }
    }

    /** @return whether connected and accepted by archon-config, so requests can be sent */
    public boolean isConnected() {
        ServiceClient current = client;
        return accepted && current != null && current.isConnected();
    }

    /**
     * The hello to send on each connection.
     *
     * @param currentRank  this instance's rank now
     * @param currentToken this instance's registration token now
     * @return the hello
     */
    protected abstract ConfigHello hello(int currentRank, String currentToken);

    /** A new connection is about to introduce itself: forget anything half received on the last one. */
    protected abstract void starting();

    /**
     * Everything after the hello, apart from answers to requests. Called on the connection's network thread.
     *
     * @param message what archon-config sent
     */
    protected abstract void received(Message message);

    /**
     * Sends a request and blocks for up to 5 seconds for the answer.
     *
     * @param operation what to do
     * @param entry     what to do it to, as the operation needs it
     * @param actor     who it is done for, recorded by archon-config; null from applications
     * @return the configurations the answer carries
     * @throws ConfigurationException if archon-config refuses, or cannot be reached or does not answer
     *                                ({@link ConfigurationException#isUnavailable()})
     */
    protected List<ConfigEntry> call(ConfigRequest.Operation operation, ConfigEntry entry, String actor) {
        ServiceClient current = client;
        if (current == null || !accepted) {
            throw ConfigurationException.unavailable(SERVICE + " is not reachable", null);
        }
        long requestId = requests.open();
        try {
            current.send(new ConfigRequest(requestId, operation, entry, actor));
        } catch (ServiceException e) {
            requests.cancel(requestId);
            throw ConfigurationException.unavailable(SERVICE + " is not reachable: " + e.getMessage(), e);
        }
        ConfigResponse response;
        try {
            response = requests.await(requestId, ConfigResponse.class, RESPONSE_TIMEOUT_MILLIS);
        } catch (ServiceException e) {
            throw ConfigurationException.unavailable(e.getMessage(), e);
        }
        if (!response.isOk()) {
            throw new ConfigurationException(response.getError());
        }
        return response.getEntries();
    }

    // Runs on the scheduler, because finding and connecting to the service blocks
    private void connect() {
        if (closed) {
            return;
        }
        try {
            client = connector.connect(SERVICE, this);
            reportedWaiting = false;
        } catch (ServiceException e) {
            boolean unknown = e.getMessage() != null && e.getMessage().startsWith("Unknown service");
            if (!reportedWaiting) {
                reportedWaiting = true;
                LOGGER.info("Configurations unavailable: {}. Retrying quietly", e.getMessage());
            }
            scheduler.schedule(this::connect, unknown ? UNKNOWN_SERVICE_RETRY_MILLIS : RETRY_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            LOGGER.warn("Cannot connect to {}: {}", SERVICE, e.getMessage());
            scheduler.schedule(this::connect, RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    // Each connection, first or after a reconnect, starts by saying who this instance is
    @Override
    public void onConnect(ServiceClient connected, ServiceEndpoint endpoint) {
        accepted = false;
        starting();
        try {
            connected.send(hello(rank.getAsInt(), token.get()));
        } catch (ServiceException e) {
            LOGGER.debug("Could not introduce this instance to {}: {}", SERVICE, e.getMessage());
        }
    }

    @Override
    public void onDisconnect(ServiceClient disconnected, ServiceEndpoint endpoint) {
        accepted = false;
        LOGGER.info("Lost {} at {}:{}; keeping the last configurations while reconnecting", SERVICE, endpoint.host(),
                endpoint.port());
    }

    @Override
    public void onMessage(ServiceClient receiver, Message message) {
        if (message instanceof ConfigHelloResponse hello) {
            if (hello.isAccepted()) {
                accepted = true;
            } else {
                // Usually brief, as while the registry fails over: start over with a fresh connection
                LOGGER.warn("{} refused this instance: {}. Retrying", SERVICE, hello.getReason());
                receiver.close();
                scheduler.schedule(this::connect, RETRY_MILLIS, TimeUnit.MILLISECONDS);
            }
        } else if (message instanceof ConfigResponse response) {
            requests.complete(response.getRequestId(), response);
        } else {
            received(message);
        }
    }
}
