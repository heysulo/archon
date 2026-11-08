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
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessWatchers.class);
    private static final ProcessWatchers INSTANCE = new ProcessWatchers();

    private final Set<Connection> watchers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pushScheduled = new AtomicBoolean(false);
    // Pushes never run on a network thread, and never two at once
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("registry-process-watch", true));

    private ProcessWatchers() {
    }

    public static ProcessWatchers getInstance() {
        return INSTANCE;
    }

    /** Subscribes a connection and sends it the current list straight away. */
    void watch(Connection connection) {
        watchers.add(connection);
        LOGGER.info("{} is watching the process list. {} watching", connection.getRemoteAddress(), watchers.size());
        pusher.execute(() -> send(connection, currentList()));
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

    int size() {
        return watchers.size();
    }

    private void pushToAll() {
        pushScheduled.set(false);
        ProcessListResponse list = currentList();
        watchers.forEach(connection -> send(connection, list));
    }

    private ProcessListResponse currentList() {
        return new ProcessListResponse(ProcessWatchRequest.PUSHED, RegistryServer.getTerm(),
                RegistryServer.getInstance().listProcesses());
    }

    private void send(Connection connection, ProcessListResponse list) {
        if (!connection.isConnected()) {
            watchers.remove(connection);
            return;
        }
        try {
            connection.send(list);
        } catch (RuntimeException e) {
            watchers.remove(connection);
            LOGGER.info("Stopped pushing processes to {}: {}", connection.getRemoteAddress(), e.getMessage());
        }
    }
}
