package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationTerminationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.AuthenticatedMessage;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(PrimaryRegistry.class);
    private static final long CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final long CONNECT_POLL_MILLIS = 100;
    private static PrimaryRegistry instance;
    private Client connection;
    private final EventLoopGroup eventLoopGroup =
            new NioEventLoopGroup(new DefaultThreadFactory("primary-registry-worker-group"));
    private volatile boolean connected = false;
    private final ArchonInterface archonInterface;
    private volatile String authenticationToken;

    private PrimaryRegistry(ArchonInterface archonInterface) {
        this.archonInterface = archonInterface;
    }

    public static synchronized PrimaryRegistry getInstance(ArchonInterface archonInterface) {
        if (instance == null) {
            instance = new PrimaryRegistry(archonInterface);
        }
        return instance;
    }

    public void connect(String host) {
        connection = new Client(host, RegistryConstants.PORT, this, eventLoopGroup);
        connection.settings().setHeartbeatInterval(RegistryConstants.HEARTBEAT_INTERVAL);
        try {
            LOGGER.info("Connecting primary registry at {}", host);
            connection.connect();
            Utils.waitForCondition(() -> connected, CONNECT_TIMEOUT_MILLIS, CONNECT_POLL_MILLIS);
        } catch (TimeoutException e) {
            LOGGER.error("Primary registry connection timed out");
            throw new PrimaryRegistryConnectionFailure(e);
        } catch (Exception e) {
            LOGGER.error("Failed to connect to primary registry server at {}. Error: {}", host, e.getMessage());
            throw new PrimaryRegistryConnectionFailure(e);
        }
    }

    @Override
    public void onConnect(Client client) {
        LOGGER.info("Connected to primary registry at {}", client.getRemoteAddress());
        connected = true;
    }

    @Override
    public void onDisconnect(Client client) {
        LOGGER.warn("Disconnected from primary registry at {}", client.getRemoteAddress());
        connected = false;
        archonInterface.reconnect();
    }

    @Override
    public void onMessage(Client client, Message message) {
        LOGGER.info("Received message from primary registry. {}", message.getClass().getName());
        if (message instanceof ApplicationRegistrationResponseMessage rankUpdateMessage) {
            this.authenticationToken = rankUpdateMessage.getAuthenticationToken();
            archonInterface.onRankUpdateMessage(rankUpdateMessage.getRank());
        } else if (message instanceof ApplicationRankUpdate rankUpdateMessage) {
            archonInterface.onRankUpdateMessage(rankUpdateMessage.getRank());
        } else if (message instanceof ApplicationTerminationMessage terminationMessage) {
            LOGGER.error("Received termination message from primary registry. Reason: {}",
                    terminationMessage.getReason());
            System.exit(1);
        }
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        LOGGER.warn("Primary registry client error", throwable);
    }

    public void sendMessage(Message message) {
        if (message instanceof AuthenticatedMessage authenticatedMessage) {
            authenticatedMessage.setAuthenticationToken(authenticationToken);
        }
        connection.send(message);
    }
}
