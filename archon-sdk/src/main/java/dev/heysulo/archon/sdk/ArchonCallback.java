package dev.heysulo.archon.sdk;

/**
 * Receives what the registry decides about this application instance. Calls arrive one at a time, in order, on an
 * SDK thread rather than a network thread, so they may call blocking SDK methods such as
 * {@link ArchonInterface#createService}.
 */
public interface ArchonCallback {

    /**
     * This instance's rank was assigned or changed. Rank 1 is the application's leader. The SDK confirms the rank to
     * the registry once this returns, so switch roles before returning.
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
}
