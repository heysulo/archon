package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.messages.ServiceWarmupMessage;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;

/** Answers clients' warm-up round trips on the network thread, and passes everything else to the application. */
final class WarmupAnsweringCallback implements ServerCallback {
    private final ServerCallback application;

    WarmupAnsweringCallback(ServerCallback application) {
        this.application = application;
    }

    @Override
    public void onMessage(Server server, Connection connection, Message message) {
        if (message instanceof ServiceWarmupMessage warmup && !warmup.isReply()) {
            connection.send(warmup.reply());
            return;
        }
        application.onMessage(server, connection, message);
    }

    @Override
    public void onConnect(Server server, Connection connection) {
        application.onConnect(server, connection);
    }

    @Override
    public void onDisconnect(Server server, Connection connection) {
        application.onDisconnect(server, connection);
    }

    @Override
    public void onError(Server server, Connection connection, Throwable cause) {
        application.onError(server, connection, cause);
    }
}
