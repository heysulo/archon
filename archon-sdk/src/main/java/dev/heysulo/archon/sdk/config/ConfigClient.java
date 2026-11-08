package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Keeps the application's configurations in memory, from archon-config. It connects in the background, says who
 * the application is, and receives every configuration it sees, then each change as it happens. After a reconnect,
 * possibly to a new archon-config primary, it receives everything again and reports only what really changed.
 */
public final class ConfigClient implements ServiceClientCallback, ConfigAdmin {
    public static final String SERVICE = "archon-config";
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigClient.class);
    private static final long RESPONSE_TIMEOUT_MILLIS = 5_000;
    // No archon-config defined: check now and then, so starting one later needs no application restart
    private static final long UNKNOWN_SERVICE_RETRY_MILLIS = 30_000;
    private static final long RETRY_MILLIS = 2_000;

    /** Opens a connection to archon-config. */
    @FunctionalInterface
    public interface Connector {
        ServiceClient connect(String serviceId, ServiceClientCallback callback);
    }

    /** Hears every value that changes: {@code oldValue} is null for a new name, {@code newValue} for a removed one. */
    @FunctionalInterface
    public interface Listener {
        void changed(String name, ConfigValue oldValue, ConfigValue newValue);
    }

    private final String group;
    private final String name;
    private final IntSupplier rank;
    private final Supplier<String> token;
    private final Connector connector;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;
    private final PendingRequests requests = new PendingRequests();
    private final CountDownLatch firstSnapshot = new CountDownLatch(1);
    private final Object cacheLock = new Object();
    private volatile Map<String, ConfigValue> cache = Map.of();
    private volatile ServiceClient client;
    private volatile boolean accepted;
    private volatile boolean reportedWaiting;
    private volatile boolean closed;

    /**
     * @param rank  this instance's current rank, which archon-config checks with the registry
     * @param token this instance's registration token
     */
    public ConfigClient(String group, String name, IntSupplier rank, Supplier<String> token, Connector connector,
            Listener listener, ScheduledExecutorService scheduler) {
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.token = token;
        this.connector = connector;
        this.listener = listener;
        this.scheduler = scheduler;
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    /** Waits until the first configurations arrive. @return false if they did not arrive in time */
    public boolean awaitFirstSnapshot(long timeoutMillis) {
        try {
            return firstSnapshot.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Optional<ConfigValue> get(String configName) {
        return Optional.ofNullable(cache.get(configName));
    }

    public Map<String, ConfigValue> all() {
        // Already unmodifiable; copyOf returns it as is
        return Map.copyOf(cache);
    }

    public boolean isConnected() {
        ServiceClient current = client;
        return accepted && current != null && current.isConnected();
    }

    /**
     * Changes the value this application sees for a name. The cache updates when archon-config pushes the change.
     *
     * @throws ConfigurationException if the name is unknown, read only, the value has the wrong type, or archon-config
     *                                cannot be reached
     */
    public ConfigValue set(String configName, String value) {
        ConfigEntry request = new ConfigEntry(0, null, null, null, configName, null, value, false, null, null, null);
        return ConfigValue.of(call(ConfigRequest.Operation.SET, request, null).get(0));
    }

    @Override
    public List<ConfigEntry> list() {
        return call(ConfigRequest.Operation.LIST_ALL, null, null);
    }

    @Override
    public ConfigEntry create(ConfigEntry entry, String actor) {
        return call(ConfigRequest.Operation.CREATE, entry, actor).get(0);
    }

    @Override
    public ConfigEntry update(ConfigEntry entry, String actor) {
        return call(ConfigRequest.Operation.UPDATE, entry, actor).get(0);
    }

    @Override
    public void delete(long id, String actor) {
        ConfigEntry target = new ConfigEntry(id, null, null, null, null, null, null, false, null, null, null);
        call(ConfigRequest.Operation.DELETE, target, actor);
    }

    public void close() {
        closed = true;
        ServiceClient current = client;
        if (current != null) {
            current.close();
        }
    }

    private List<ConfigEntry> call(ConfigRequest.Operation operation, ConfigEntry entry, String actor) {
        ServiceClient current = client;
        if (current == null || !accepted) {
            throw ConfigurationException.unavailable("archon-config is not reachable", null);
        }
        long requestId = requests.open();
        try {
            current.send(new ConfigRequest(requestId, operation, entry, actor));
        } catch (ServiceException e) {
            requests.cancel(requestId);
            throw ConfigurationException.unavailable("archon-config is not reachable: " + e.getMessage(), e);
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

    // Each connection, first or after a reconnect, starts by saying who this application is
    @Override
    public void onConnect(ServiceClient connected, ServiceEndpoint endpoint) {
        accepted = false;
        try {
            connected.send(new ConfigHello(group, name, rank.getAsInt(), token.get()));
        } catch (ServiceException e) {
            LOGGER.debug("Could not introduce this application to {}: {}", SERVICE, e.getMessage());
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
                replace(hello.getEntries());
                firstSnapshot.countDown();
                LOGGER.info("Received {} configurations from {}", hello.getEntries().size(), SERVICE);
            } else {
                // Usually brief, as while the registry fails over: start over with a fresh connection
                LOGGER.warn("{} refused this application: {}. Retrying", SERVICE, hello.getReason());
                receiver.close();
                scheduler.schedule(this::connect, RETRY_MILLIS, TimeUnit.MILLISECONDS);
            }
        } else if (message instanceof ConfigChanged changed) {
            applyChanges(changed);
        } else if (message instanceof ConfigResponse response) {
            requests.complete(response.getRequestId(), response);
        }
    }

    private void replace(List<ConfigEntry> entries) {
        Map<String, ConfigValue> next = new HashMap<>();
        entries.forEach(entry -> next.put(entry.name(), ConfigValue.of(entry)));
        swap(next);
    }

    private void applyChanges(ConfigChanged changed) {
        Map<String, ConfigValue> next = new HashMap<>(cache);
        changed.getChanged().forEach(entry -> next.put(entry.name(), ConfigValue.of(entry)));
        changed.getRemoved().forEach(next::remove);
        swap(next);
    }

    // Tells the listener about every name whose value, type, level or read-only flag differs
    private void swap(Map<String, ConfigValue> next) {
        Map<String, ConfigValue> previous;
        synchronized (cacheLock) {
            previous = cache;
            cache = Map.copyOf(next);
        }
        Set<String> names = new HashSet<>(previous.keySet());
        names.addAll(next.keySet());
        for (String configName : names) {
            ConfigValue before = previous.get(configName);
            ConfigValue after = next.get(configName);
            if (before == null ? after != null : !before.equals(after)) {
                listener.changed(configName, before, after);
            }
        }
    }
}
