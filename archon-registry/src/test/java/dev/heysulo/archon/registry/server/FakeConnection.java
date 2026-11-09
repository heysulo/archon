package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationTerminationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.AuthenticatedMessage;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.ConnectionCallback;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the registry sends, and lets a test drop the connection. */
public final class FakeConnection implements Connection {
    private final List<Message> sent = new CopyOnWriteArrayList<>();
    private volatile boolean connected = true;
    private volatile ConnectionCallback callback;

    public void drop() {
        connected = false;
        callback.onDisconnect(this);
    }

    public String termination() {
        ApplicationTerminationMessage message = last(ApplicationTerminationMessage.class);
        return message == null ? null : message.getReason();
    }

    public Message lastSent() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    public <T extends Message> T last(Class<T> type) {
        return sent.stream().filter(type::isInstance).map(type::cast).reduce((a, b) -> b).orElse(null);
    }

    @Override
    public void send(Message message) {
        if (message instanceof AuthenticatedMessage) {
            Assert.assertTrue(connected, "sent on a dropped connection");
        }
        sent.add(message);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public OptionalLong getLatencyMillis() {
        return OptionalLong.empty();
    }

    @Override
    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }

    @Override
    public String getRemoteAddress() {
        return "fake";
    }

    @Override
    public int getRemotePort() {
        return 0;
    }
}
