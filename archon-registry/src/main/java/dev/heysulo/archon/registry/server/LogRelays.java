package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.LogBatch;
import dev.heysulo.archon.dictionary.sdk.messages.LogStreamControl;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessLogBatch;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.databridge.core.common.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays application instances' log lines to the connections watching them, such as the portal's. An instance sends
 * lines only while it has a watcher: it is told to start with the first and to stop after the last, and asked again
 * when it reconnects. Nothing is stored here.
 */
public final class LogRelays {
    static final String NOT_RUNNING = "The instance is not running";
    static final String DISCONNECTED = "The instance disconnected. Waiting for it to come back";
    private static final Logger LOGGER = LoggerFactory.getLogger(LogRelays.class);
    private static final LogRelays INSTANCE = new LogRelays();

    private final Map<Application, Set<Connection>> watchers = new ConcurrentHashMap<>();

    private LogRelays() {
    }

    public static LogRelays getInstance() {
        return INSTANCE;
    }

    /** Starts relaying an instance's lines to a connection. */
    void watch(Connection watcher, Application application) {
        if (application == null || !application.isRunning()) {
            return;
        }
        boolean first;
        synchronized (this) {
            Set<Connection> set = watchers.computeIfAbsent(application, key -> ConcurrentHashMap.newKeySet());
            first = set.isEmpty();
            set.add(watcher);
        }
        LOGGER.info("{} is watching the logs of {}", watcher.getRemoteAddress(), application.getDisplayName());
        if (first) {
            control(application, true);
        }
    }

    void unwatch(Connection watcher, Application application) {
        if (application != null && remove(watcher, application)) {
            control(application, false);
        }
    }

    /** A watcher's connection closed: everything it watched loses it. */
    void disconnected(Connection watcher) {
        watchers.keySet().forEach(application -> {
            if (remove(watcher, application)) {
                control(application, false);
            }
        });
    }

    // Whether that was the last watcher
    private synchronized boolean remove(Connection watcher, Application application) {
        Set<Connection> set = watchers.get(application);
        if (set == null || !set.remove(watcher)) {
            return false;
        }
        if (set.isEmpty()) {
            watchers.remove(application);
            LOGGER.info("Nobody is watching the logs of {} any more", application.getDisplayName());
            return true;
        }
        return false;
    }

    public boolean isWatched(Application application) {
        Set<Connection> set = watchers.get(application);
        return set != null && !set.isEmpty();
    }

    /** Lines from an instance, passed to its watchers. */
    public void fromInstance(Application application, LogBatch batch) {
        Set<Connection> set = watchers.get(application);
        if (set == null || set.isEmpty()) {
            // Watching stopped while this was on its way; make sure the instance knows
            control(application, false);
            return;
        }
        relay(application, set, new ProcessLogBatch(application.getGroup(), application.getName(),
                application.getRank(), batch.getLines(), batch.getDropped(), batch.isHistory(), null));
    }

    /** An instance (re)connected, as after a failover: if anyone is watching, it should send its lines again. */
    public void connected(Application application) {
        if (isWatched(application)) {
            control(application, true);
        }
    }

    /** An instance's connection dropped: watchers hear so, and keep watching in case it comes back. */
    public void lost(Application application) {
        Set<Connection> set = watchers.get(application);
        if (set != null && !set.isEmpty()) {
            relay(application, set, ProcessLogBatch.status(application.getGroup(), application.getName(),
                    application.getRank(), DISCONNECTED));
        }
    }

    private void relay(Application application, Set<Connection> set, ProcessLogBatch batch) {
        set.forEach(watcher -> {
            if (!watcher.isConnected()) {
                unwatch(watcher, application);
                return;
            }
            try {
                watcher.send(batch);
            } catch (RuntimeException e) {
                LOGGER.debug("Cannot relay logs to {}: {}", watcher.getRemoteAddress(), e.getMessage());
            }
        });
    }

    private static void control(Application application, boolean streaming) {
        if (!application.isConnected()) {
            return;
        }
        try {
            application.send(new LogStreamControl(streaming));
        } catch (RuntimeException e) {
            LOGGER.debug("Cannot tell {} to {} streaming logs: {}", application.getDisplayName(),
                    streaming ? "start" : "stop", e.getMessage());
        }
    }

    void clear() {
        watchers.clear();
    }
}
