package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;

/** One connection to one instance of a service. Lets tests stand in for the network. */
interface ServiceConnection {

    /** Opens connections to service instances. */
    @FunctionalInterface
    interface Connector {
        ServiceConnection open(ServiceEndpoint endpoint, Listener listener);

        /**
         * Opens a connection to an instance of a service, knowing its definition, which says who must be serving it.
         */
        default ServiceConnection open(dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition definition,
                ServiceEndpoint endpoint, Listener listener) {
            return open(endpoint, listener);
        }
    }

    /** What happens on the connection. */
    interface Listener {
        void onConnect();

        void onDisconnect();

        void onMessage(Message message);

        void onError(Throwable cause);
    }

    /** Blocks until the transport connects. The handshake completes after, reported through the listener. */
    void connect() throws InterruptedException;

    void send(Message message);

    boolean isConnected();

    void close();
}
