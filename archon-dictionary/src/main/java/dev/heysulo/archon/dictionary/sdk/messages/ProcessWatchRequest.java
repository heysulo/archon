package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * Subscribes the sending connection to the primary registry's process list. The registry answers at once with a
 * {@link ProcessListResponse}, then sends a new one whenever an application instance changes, until the connection
 * closes. Pushed lists carry {@link #PUSHED} as their request id.
 */
public class ProcessWatchRequest implements Message {
    public static final long PUSHED = -1;
    private static final long serialVersionUID = 1L;
}
