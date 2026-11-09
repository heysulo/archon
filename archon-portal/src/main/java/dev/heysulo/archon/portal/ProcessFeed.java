package dev.heysulo.archon.portal;

/** The process list as it changes. */
interface ProcessFeed {

    /**
     * @throws PortalException if no primary registry is answering
     */
    ProcessSnapshot current();

    void subscribe(Listener listener);

    /**
     * Relays an application instance's log lines to the listener until the returned handle is run. Calls may come
     * from any thread and must not block.
     *
     * @throws PortalException if live logs are not available
     */
    default Runnable watchLogs(String group, String name, int rank,
            java.util.function.Consumer<dev.heysulo.archon.dictionary.sdk.messages.ProcessLogBatch> listener) {
        throw new PortalException("Live logs are not available");
    }

    /** Hears every change. Calls may come from any thread and must not block. */
    interface Listener {
        void onSnapshot(ProcessSnapshot snapshot);

        void onLost(String reason);
    }
}
