package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.sdk.tls.ArchonTls;
import io.netty.handler.ssl.SslContext;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.common.ProtocolSettings;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.time.Duration;
import java.util.function.Consumer;

/** Connects to service instances over DataBridge, sharing one set of network threads across all service clients. */
final class DataBridgeServiceConnector implements ServiceConnection.Connector {
    // A primary that has just died often leaves its address unanswered. Failing fast lets the client look the
    // service up again instead of waiting out the default 30 s.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final EventLoopGroup EVENT_LOOPS =
            new NioEventLoopGroup(new DefaultThreadFactory("archon-service-client", true));

    private final Consumer<ProtocolSettings> configure;

    /**
     * @param configure adjusts each connection's settings, for example trusted packages, before it connects
     */
    DataBridgeServiceConnector(Consumer<ProtocolSettings> configure) {
        this.configure = configure;
    }

    @Override
    public ServiceConnection open(ServiceEndpoint endpoint, ServiceConnection.Listener listener) {
        return open(null, endpoint, listener);
    }

    /**
     * Connects with TLS when the instance serves it, and requires the instance to prove it is the service's owner.
     * With TLS required, an instance serving in the clear is refused.
     */
    @Override
    public ServiceConnection open(ServiceDefinition definition, ServiceEndpoint endpoint,
            ServiceConnection.Listener listener) {
        ArchonTls tls = ArchonTls.get();
        SslContext context = null;
        if (tls.enabled() && endpoint.secure() && definition != null) {
            context = tls.serviceClientContext(new ArchonIdentity(definition.group(), definition.application()));
        } else if (tls.required()) {
            throw new ServiceException(String.format("The instance at %s:%d does not serve TLS, which is required",
                    endpoint.host(), endpoint.port()));
        }
        Client client = new Client(endpoint.host(), endpoint.port(), new ClientCallback() {
            @Override
            public void onConnect(Client connected) {
                listener.onConnect();
            }

            @Override
            public void onDisconnect(Client disconnected) {
                listener.onDisconnect();
            }

            @Override
            public void onMessage(Client receiver, Message message) {
                listener.onMessage(message);
            }

            @Override
            public void onError(Client failed, Throwable cause) {
                listener.onError(cause);
            }
        }, context, EVENT_LOOPS);
        // Instances are trusted by the identity in their certificate, not by address
        client.setHostnameVerificationEnabled(false);
        client.settings().setConnectTimeout(CONNECT_TIMEOUT);
        configure.accept(client.settings());
        return new ServiceConnection() {
            @Override
            public void connect() throws InterruptedException {
                client.connect();
            }

            @Override
            public void send(Message message) {
                client.send(message);
            }

            @Override
            public boolean isConnected() {
                return client.isConnected();
            }

            @Override
            public void close() {
                try {
                    client.stop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }
}
