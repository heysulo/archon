package dev.heysulo.archon.portal;

import dev.heysulo.archon.dictionary.sdk.messages.ProcessLogBatch;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessLogWatch;
import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessListRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessListResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessWatchRequest;
import dev.heysulo.archon.sdk.registry.RegistryLookupService;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the primary registry's process list. It finds the primary the way applications do, subscribes, and from
 * then on the registry pushes a new list on every change. A check every few seconds catches a primary that stops
 * answering, as after a failover, and the watcher then finds the new one and subscribes again.
 */
final class RegistryWatcher implements ClientCallback, ProcessFeed {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryWatcher.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(3);
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 3_000;
    private static final long RETRY_MILLIS = 1_000;
    private static final long CHECK_INTERVAL_MILLIS = 5_000;
    // Without an answer for this long, the registry is treated as gone even if the connection looks open
    private static final long SILENCE_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final long CHECK_REQUEST_ID = 0;

    private final EventLoopGroup eventLoops =
            new NioEventLoopGroup(1, new DefaultThreadFactory("portal-registry", true));
    // Connecting and checking run here, one at a time, never on a network thread
    private final ScheduledExecutorService watchThread = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("portal-registry-watch", true));
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Client client;
    private volatile String location;
    private volatile CountDownLatch handshake = new CountDownLatch(1);
    private volatile ProcessSnapshot latest;
    private volatile long lastHeardNanos;
    private volatile String lostReason = "Connecting to the registry";
    // Log watchers by instance, as group:name:rank. The registry is asked once per instance, however many watch
    private final java.util.Map<String, List<java.util.function.Consumer<ProcessLogBatch>>> logWatchers =
            new java.util.concurrent.ConcurrentHashMap<>();

    void start() {
        watchThread.execute(this::connect);
        watchThread.scheduleWithFixedDelay(this::check, CHECK_INTERVAL_MILLIS, CHECK_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public ProcessSnapshot current() {
        ProcessSnapshot snapshot = latest;
        if (snapshot == null) {
            throw new PortalException(lostReason);
        }
        return snapshot;
    }

    @Override
    public void subscribe(Listener listener) {
        listeners.add(listener);
    }

    private void connect() {
        Client current = client;
        if (current != null && current.isConnected()) {
            return;
        }
        String primary = RegistryLookupService.getInstance().search();
        if (primary == null) {
            lost("No primary registry is answering");
            retryLater();
            return;
        }
        handshake = new CountDownLatch(1);
        // With TLS on, proves this component's identity and trusts only a registry; null, so plain, until joined
        Client fresh = new Client(primary, RegistryProtocol.PORT, this,
                dev.heysulo.archon.sdk.tls.ArchonTls.get().registryContext(), eventLoops);
        fresh.setHostnameVerificationEnabled(false);
        fresh.settings().setConnectTimeout(CONNECT_TIMEOUT);
        fresh.settings().setHeartbeatInterval(HEARTBEAT_INTERVAL);
        try {
            fresh.connect();
            if (!handshake.await(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("no handshake");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            // Netty rethrows checked connect failures without declaring them
            stop(fresh);
            lost("Cannot connect to the registry at " + primary + ": " + e.getMessage());
            retryLater();
            return;
        }
        client = fresh;
        location = primary;
        lastHeardNanos = System.nanoTime();
        LOGGER.info("Watching the process list of the primary registry at {}", primary);
        fresh.send(new ProcessWatchRequest());
        // A new primary knows nothing of the logs being watched through the last one
        logWatchers.keySet().forEach(key -> sendLogWatch(fresh, key, true));
    }

    private void check() {
        Client current = client;
        if (current == null || !current.isConnected()) {
            connect();
            return;
        }
        if (System.nanoTime() - lastHeardNanos > SILENCE_LIMIT_NANOS) {
            // A registry that is no longer primary drops the requests without closing the connection
            client = null;
            stop(current);
            lost("The registry at " + location + " stopped answering. It may no longer be primary");
            connect();
            return;
        }
        try {
            current.send(new ProcessListRequest(CHECK_REQUEST_ID));
        } catch (RuntimeException e) {
            LOGGER.debug("Check of the registry failed: {}", e.getMessage());
        }
    }

    private void retryLater() {
        watchThread.schedule(this::connect, RETRY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void lost(String reason) {
        boolean wasLive = latest != null;
        latest = null;
        lostReason = reason;
        if (wasLive) {
            LOGGER.warn("Process list unavailable: {}", reason);
            listeners.forEach(listener -> listener.onLost(reason));
        }
    }

    private static void stop(Client failed) {
        try {
            failed.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onConnect(Client connected) {
        handshake.countDown();
    }

    @Override
    public void onDisconnect(Client disconnected) {
        if (client == disconnected) {
            client = null;
            lost("Lost the registry at " + location);
            watchThread.execute(this::connect);
        }
    }

    @Override
    public Runnable watchLogs(String group, String name, int rank,
            java.util.function.Consumer<ProcessLogBatch> listener) {
        String key = logKey(group, name, rank);
        boolean first;
        synchronized (logWatchers) {
            List<java.util.function.Consumer<ProcessLogBatch>> list = logWatchers.computeIfAbsent(key,
                    ignored -> new CopyOnWriteArrayList<>());
            first = list.isEmpty();
            list.add(listener);
        }
        Client current = client;
        if (first && current != null && current.isConnected()) {
            sendLogWatch(current, key, true);
        }
        return () -> {
            boolean last;
            synchronized (logWatchers) {
                List<java.util.function.Consumer<ProcessLogBatch>> list = logWatchers.get(key);
                last = list != null && list.remove(listener) && list.isEmpty();
                if (last) {
                    logWatchers.remove(key);
                }
            }
            Client now = client;
            if (last && now != null && now.isConnected()) {
                sendLogWatch(now, key, false);
            }
        };
    }

    static String logKey(String group, String name, int rank) {
        return group + ":" + name + ":" + rank;
    }

    private static void sendLogWatch(Client to, String key, boolean watch) {
        int last = key.lastIndexOf(':');
        int first = key.indexOf(':');
        try {
            to.send(new ProcessLogWatch(key.substring(0, first), key.substring(first + 1, last),
                    Integer.parseInt(key.substring(last + 1)), watch));
        } catch (RuntimeException e) {
            LOGGER.debug("Cannot {} the logs of {}: {}", watch ? "watch" : "stop watching", key, e.getMessage());
        }
    }

    @Override
    public void onMessage(Client receiver, Message message) {
        if (receiver == client && message instanceof ProcessLogBatch batch) {
            List<java.util.function.Consumer<ProcessLogBatch>> list = logWatchers.get(
                    logKey(batch.getGroup(), batch.getName(), batch.getRank()));
            if (list != null) {
                list.forEach(listener -> listener.accept(batch));
            }
            return;
        }
        if (receiver != client || !(message instanceof ProcessListResponse response)) {
            return;
        }
        lastHeardNanos = System.nanoTime();
        ProcessSnapshot previous = latest;
        ProcessSnapshot snapshot = ProcessSnapshot.of(location, response.getTerm(), response.getProcesses());
        latest = snapshot;
        // The periodic check answers too; only pass on what actually changed
        if (previous == null || !previous.sameContent(snapshot)) {
            listeners.forEach(listener -> listener.onSnapshot(snapshot));
        }
    }

    @Override
    public void onError(Client failed, Throwable cause) {
        LOGGER.warn("Registry connection error: {}", cause.getMessage());
    }
}
