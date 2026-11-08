package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;

/**
 * Receives what happens on a connection to a service, given to
 * {@link dev.heysulo.archon.sdk.ArchonInterface#connectToService(String, ServiceClientCallback)}. Calls arrive on
 * the connection's network thread, so they must not block: a slow call holds up every message after it. Hand longer
 * work to another thread.
 */
public interface ServiceClientCallback {

    /**
     * A message arrived from the service.
     *
     * @param client  the client it arrived on
     * @param message the message
     */
    void onMessage(ServiceClient client, Message message);

    /**
     * Connected to an instance of the service, first or after reconnecting, and warmed up if the client warms up.
     * Messages can be sent from here on.
     *
     * @param client   the connected client
     * @param endpoint the instance connected to
     */
    default void onConnect(ServiceClient client, ServiceEndpoint endpoint) {
    }

    /**
     * The connection dropped. The client is already looking the service up to reconnect, possibly to another
     * instance; until {@link #onConnect} is called again, sending fails.
     *
     * @param client   the disconnected client
     * @param endpoint the instance it was connected to
     */
    default void onDisconnect(ServiceClient client, ServiceEndpoint endpoint) {
    }

    /**
     * Something went wrong on the connection, such as a message that could not be read. The connection may stay up;
     * if it drops, {@link #onDisconnect} follows.
     *
     * @param client the client it happened on
     * @param cause  what went wrong
     */
    default void onError(ServiceClient client, Throwable cause) {
    }
}
