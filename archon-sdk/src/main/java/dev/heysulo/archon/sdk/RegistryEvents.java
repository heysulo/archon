package dev.heysulo.archon.sdk;

import dev.heysulo.archon.sdk.config.ConfigValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * Hands registry events to the application's callback away from the network thread. A callback that calls back
 * into the SDK and waits for the registry would otherwise block the very thread the answer arrives on.
 */
final class RegistryEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryEvents.class);

    private final ArchonCallback callback;
    private final Executor executor;

    /**
     * @param executor runs callbacks one at a time, in order
     */
    RegistryEvents(ArchonCallback callback, Executor executor) {
        this.callback = callback;
        this.executor = executor;
    }

    /** Tells the callback. The application acknowledges the rank itself, once it is ready for it. */
    void rankChanged(int rank) {
        executor.execute(() -> {
            try {
                callback.onRankUpdateMessage(rank);
            } catch (RuntimeException e) {
                LOGGER.error("Rank callback failed for rank {}; the rank stays unacknowledged", rank, e);
            }
        });
    }

    void configChanged(String name, ConfigValue oldValue,
            ConfigValue newValue) {
        executor.execute(() -> {
            try {
                callback.onConfigUpdated(name, oldValue, newValue);
            } catch (RuntimeException e) {
                LOGGER.error("Configuration callback failed for {}", name, e);
            }
        });
    }

    void terminated(String reason) {
        executor.execute(() -> callback.onTerminated(reason));
    }
}
