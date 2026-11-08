package dev.heysulo.archon.sdk;

import dev.heysulo.archon.sdk.config.ConfigValue;

/**
 * Receives what the registry decides about this application instance. Calls arrive one at a time, in order, on an
 * SDK thread rather than a network thread, so they may call blocking SDK methods such as
 * {@link ArchonInterface#createService}.
 */
/**
 * What an application hears from Archon, passed to {@link ArchonInterface#initialize}.
 *
 * <p>Every method runs on the SDK's single callback thread, one call at a time and in the order the events
 * happened, never on a network thread. A slow callback delays the ones after it, so hand long work to another
 * thread; an exception thrown from one is logged and does not stop later ones.
 */
public interface ArchonCallback {

    /**
     * This instance's rank was assigned or changed. Rank 1 is the application's leader. The registry holds the
     * instance as starting (or changing) until it calls {@link ArchonInterface#acknowledgeRank} with this rank: do
     * whatever the new role needs first, such as starting its services, connecting to others' and warming up, then
     * acknowledge. Only acknowledged instances can be promoted when the leader goes. This may run again with a new
     * rank before the last one was acknowledged; acknowledge the newest.
     *
     * <p>Called with the first rank too, the one {@link ArchonInterface#initialize} returns.
     *
     * @param newRank the rank assigned: 1 for the leader, higher for mirrors
     */
    void onRankUpdateMessage(int newRank);

    /**
     * The registry told this instance to stop, for example because another instance holds its rank. The SDK has
     * already stopped talking to the registry and will not reconnect. By default this ends the process, so its
     * orchestrator can start a fresh instance. Override it to shut down more gracefully, but do stop acting on the
     * old rank.
     *
     * @param reason why the registry terminated this instance, such as another instance holding its rank
     */
    default void onTerminated(String reason) {
        ArchonTermination.exitProcess(reason);
    }

    /**
     * A configuration this instance sees changed, was added ({@code oldValue} is null) or removed ({@code newValue}
     * is null). Also called after a reconnect for values that changed meanwhile, and on startup for the first
     * values, each as added. Only changes to what this instance sees are reported: a change to a global value hidden
     * by this application's own is not.
     *
     * <p>{@link ArchonInterface#getConfig} already returns the new value when this is called.
     *
     * @param name     the configuration's name
     * @param oldValue what this instance saw before, or null if the name is new to it
     * @param newValue what it sees now, or null if the name is no longer visible to it
     */
    default void onConfigUpdated(String name, ConfigValue oldValue,
            ConfigValue newValue) {
    }
}
