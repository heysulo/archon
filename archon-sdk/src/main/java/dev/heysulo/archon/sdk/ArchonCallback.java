package dev.heysulo.archon.sdk;

import dev.heysulo.archon.sdk.config.ConfigValue;

/**
 * Receives what the registry decides about this application instance. Calls arrive one at a time, in order, on an
 * SDK thread rather than a network thread, so they may call blocking SDK methods such as
 * {@link ArchonInterface#createService}.
 */
public interface ArchonCallback {

    /**
     * This instance's rank was assigned or changed. Rank 1 is the application's leader. The registry holds the
     * instance as starting (or changing) until it calls {@link ArchonInterface#acknowledgeRank} with this rank: do
     * whatever the new role needs first, such as starting its services, connecting to others' and warming up, then
     * acknowledge. Only acknowledged instances can be promoted when the leader goes. This may run again with a new
     * rank before the last one was acknowledged; acknowledge the newest.
     */
    void onRankUpdateMessage(int newRank);

    /**
     * The registry told this instance to stop, for example because another instance holds its rank. The SDK has
     * already stopped talking to the registry and will not reconnect. By default this ends the process, so its
     * orchestrator can start a fresh instance. Override it to shut down more gracefully, but do stop acting on the
     * old rank.
     */
    default void onTerminated(String reason) {
        ArchonTermination.exitProcess(reason);
    }

    /**
     * A configuration this instance sees changed, was added ({@code oldValue} is null) or removed ({@code newValue}
     * is null). Also called after a reconnect for values that changed meanwhile.
     */
    default void onConfigUpdated(String name, ConfigValue oldValue,
            ConfigValue newValue) {
    }
}
