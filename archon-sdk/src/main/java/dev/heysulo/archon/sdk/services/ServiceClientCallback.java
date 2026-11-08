package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;

/** Receives what happens on a connection to a service. Calls arrive on a network thread, so they must not block. */
public interface ServiceClientCallback {
    void onMessage(ServiceClient client, Message message);

    /** Connected to an instance of the service, first or after reconnecting. */
    default void onConnect(ServiceClient client, ServiceEndpoint endpoint) {
    }

    /** The connection dropped. The client is already looking for an instance to reconnect to. */
    default void onDisconnect(ServiceClient client, ServiceEndpoint endpoint) {
    }

    default void onError(ServiceClient client, Throwable cause) {
    }
}
