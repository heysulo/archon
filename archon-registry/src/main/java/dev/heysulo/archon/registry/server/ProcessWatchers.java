package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ProcessListResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessWatchRequest;
import dev.heysulo.databridge.core.common.Connection;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

/**
 * Connections watching the process list, such as the portal. Every change to an application instance or its
 * services schedules one push; changes that land within the debounce window share it, so a burst of registrations
 * sends one list rather than dozens.
 */
public final class ProcessWatchers {
    static final long DEBOUNCE_MILLIS = 100;
    // Instances report CPU and memory every few seconds, at different moments; their reports share a push
    static final long METRICS_DEBOUNCE_MILLIS = 2_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessWatchers.class);
    private static final ProcessWatchers INSTANCE = new ProcessWatchers();

    private final Set<Connection> watchers = ConcurrentHashMap.newKeySet();
    // Watchers that can read metrics; older portals are sent lists without them
    private final Set<Connection> metricsWatchers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean metricsPushScheduled = new AtomicBoolean(false);
    // Pushes never run on a network thread, and never two at once
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("registry-process-watch", true));

    private ProcessWatchers() {
    }

    public static ProcessWatchers getInstance() {
        return INSTANCE;
    }

    /** Subscribes a connection and sends it the current list straight away. */
    void watch(Connection connection, boolean withMetrics) {
        watchers.add(connection);
        if (withMetrics) {
            metricsWatchers.add(connection);
        }
        LOGGER.info("{} is watching the process list. {} watching", connection.getRemoteAddress(), watchers.size());
        pusher.execute(() -> send(connection, currentList(withMetrics)));
    }

    /** Something an instance shows changed. Cheap to call often; pushes at most once per debounce window. */
    public void changed() {
        if (watchers.isEmpty() || RegistryServer.getRank() != RANK_PRIMARY) {
            return;
        }
        if (pushScheduled.compareAndSet(false, true)) {
            pusher.schedule(this::pushToAll, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * An instance reported its CPU and memory. Pushes at most once per {@value #METRICS_DEBOUNCE_MILLIS} ms for these,
     * so a large fleet reporting every few seconds does not send the list many times a second.
     */
    public void metricsChanged() {
        if (watchers.isEmpty() || RegistryServer.getRank() != RANK_PRIMARY) {
            return;
        }
        if (metricsPushScheduled.compareAndSet(false, true)) {
            pusher.schedule(() -> {
                metricsPushScheduled.set(false);
                pushToAll();
            }, METRICS_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    int size() {
        return watchers.size();
    }

    private void pushToAll() {
        pushScheduled.set(false);
        ProcessListResponse withMetrics = currentList(true);
        ProcessListResponse without = null;
        for (Connection connection : watchers) {
            if (metricsWatchers.contains(connection)) {
                send(connection, withMetrics);
            } else {
                if (without == null) {
                    without = currentList(false);
                }
                send(connection, without);
            }
        }
    }

    private ProcessListResponse currentList(boolean withMetrics) {
        return new ProcessListResponse(ProcessWatchRequest.PUSHED, RegistryServer.getTerm(),
                RegistryServer.getInstance().listProcesses(withMetrics));
    }

    private void send(Connection connection, ProcessListResponse list) {
        if (!connection.isConnected()) {
            watchers.remove(connection);
            metricsWatchers.remove(connection);
            return;
        }
        try {
            connection.send(list);
        } catch (RuntimeException e) {
            watchers.remove(connection);
            metricsWatchers.remove(connection);
            LOGGER.info("Stopped pushing processes to {}: {}", connection.getRemoteAddress(), e.getMessage());
        }
    }
}
