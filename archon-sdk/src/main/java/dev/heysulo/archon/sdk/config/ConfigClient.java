package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHello;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigSnapshot;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
public final class ConfigClient extends ConfigConnection {
    public static final String SERVICE = ConfigConnection.SERVICE;
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigClient.class);

    /** Hears every value that changes: {@code oldValue} is null for a new name, {@code newValue} for a removed one. */
    @FunctionalInterface
    public interface Listener {
        void changed(String name, ConfigValue oldValue, ConfigValue newValue);
    }

    private final Listener listener;
    private final CountDownLatch firstSnapshot = new CountDownLatch(1);
    private final Object cacheLock = new Object();
    private volatile Map<String, ConfigValue> cache = Map.of();
    // The pieces of a snapshot so far; used once the last arrives. Touched only by the connection's thread.
    private List<ConfigEntry> incoming;

    /**
     * @param rank  this instance's current rank, which archon-config checks with the registry
     * @param token this instance's registration token
     */
    public ConfigClient(String group, String name, IntSupplier rank, Supplier<String> token, Connector connector,
            Listener listener, ScheduledExecutorService scheduler) {
        super(group, name, rank, token, connector, scheduler);
        this.listener = listener;
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
    protected ConfigHello hello(int currentRank, String currentToken) {
        return new ConfigHello(group, name, currentRank, currentToken);
    }

    @Override
    protected void starting() {
        incoming = null;
    }

    @Override
    protected void received(Message message) {
        if (message instanceof ConfigSnapshot snapshot) {
            if (incoming == null) {
                incoming = new ArrayList<>();
            }
            incoming.addAll(snapshot.getEntries());
            if (snapshot.isLast()) {
                List<ConfigEntry> entries = incoming;
                incoming = null;
                replace(entries);
                firstSnapshot.countDown();
                LOGGER.info("Received {} configurations from {}", entries.size(), SERVICE);
            }
        } else if (message instanceof ConfigChanged changed) {
            applyChanges(changed);
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
