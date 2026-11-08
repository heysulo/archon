package dev.heysulo.archon.portal;

/** The process list as it changes. */
interface ProcessFeed {

    /**
     * @throws PortalException if no primary registry is answering
     */
    ProcessSnapshot current();

    void subscribe(Listener listener);

    /** Hears every change. Calls may come from any thread and must not block. */
    interface Listener {
        void onSnapshot(ProcessSnapshot snapshot);

        void onLost(String reason);
    }
}
