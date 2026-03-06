package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.sdk.ArchonInterface;
import dev.heysulo.archon.sdk.constant.RegistryConstants;
import dev.heysulo.archon.sdk.exception.PrimaryRegistryConnectionFailure;
import dev.heysulo.archon.sdk.utils.Utils;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

public class PrimaryRegistry implements ClientCallback {
    private static Logger logger = LoggerFactory.getLogger(PrimaryRegistry.class);
    private static PrimaryRegistry instance;
    private Client connection;
    private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(new DefaultThreadFactory("primary-registry-worker-group"));
    private boolean connected = false;
    private final ArchonInterface archonInterface;

    public PrimaryRegistry(ArchonInterface archonInterface) {
        this.archonInterface = archonInterface;
    }

    public static PrimaryRegistry getInstance(ArchonInterface archonInterface) {
        if (instance == null) {
            instance = new PrimaryRegistry(archonInterface);
        }
        return instance;
    }

    public void connect(String host) {
        connection = new Client(host, RegistryConstants.PORT, this, eventLoopGroup);
        try {
            logger.info("Connecting primary registry at {}", host);
            connection.connect();
            Utils.waitForCondition(() -> connected, 5_000, 100);
        } catch (TimeoutException e) {
            logger.error("Primary registry connection timed out");
            throw new PrimaryRegistryConnectionFailure(e);
        } catch (Exception e) {
            logger.error("Failed to connect to primary registry server at {}. Error: {}", host, e.getMessage());
            throw new PrimaryRegistryConnectionFailure(e);
        }
    }

    @Override
    public void OnConnect(Client client) {
        logger.info("Connected to primary registry at {}", client.getRemoteAddress());
        connected = true;
    }

    @Override
    public void OnDisconnect(Client client) {
        logger.warn("Disconnected from primary registry at {}", client.getRemoteAddress());
        connected = false;
        archonInterface.reconnect();
    }

    @Override
    public void OnMessage(Client client, Message message) {
        logger.info("Received message from primary registry. {}", message.getClass().getName());
        if (message instanceof ApplicationRegistrationResponseMessage rankUpdateMessage) {
            archonInterface.onRankUpdateMessage(rankUpdateMessage.getRank());
        } else if (message instanceof ApplicationRankUpdate rankUpdateMessage) {
            archonInterface.onRankUpdateMessage(rankUpdateMessage.getRank());
        }
    }

    @Override
    public void OnError(Client client, Throwable throwable) {
        logger.warn("Primary registry client error", throwable);
    }

    public void sendMessage(Message message) {
        connection.send(message);
    }
}
