package dev.heysulo.archon.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Hands registry events to the application's callback away from the network thread. A callback that calls back
 * into the SDK and waits for the registry would otherwise block the very thread the answer arrives on.
 */
final class RegistryEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryEvents.class);

    private final ArchonCallback callback;
    private final IntConsumer acknowledge;
    private final Executor executor;

    /**
     * @param acknowledge confirms a rank to the registry
     * @param executor    runs callbacks one at a time, in order
     */
    RegistryEvents(ArchonCallback callback, IntConsumer acknowledge, Executor executor) {
        this.callback = callback;
        this.acknowledge = acknowledge;
        this.executor = executor;
    }

    /** Tells the callback, then confirms the rank, so the registry only counts the instance once it has switched. */
    void rankChanged(int rank) {
        executor.execute(() -> {
            try {
                callback.onRankUpdateMessage(rank);
            } catch (RuntimeException e) {
                // The registry assigned the rank regardless; withholding the confirmation would only leave this
                // instance counted as starting forever
                LOGGER.error("Rank callback failed for rank {}", rank, e);
            }
            acknowledge.accept(rank);
        });
    }

    void terminated(String reason) {
        executor.execute(() -> callback.onTerminated(reason));
    }
}
