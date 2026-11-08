package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationTerminationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.AuthenticatedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartResponseMessage;
import dev.heysulo.archon.sdk.ArchonInterface;
import dev.heysulo.archon.sdk.constant.RegistryConstants;
import dev.heysulo.archon.sdk.exception.PrimaryRegistryConnectionFailure;
import dev.heysulo.archon.sdk.utils.Utils;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.EventLoops;
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
    private volatile Client connection;
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
        connected = false;
        Client client = new Client(host, RegistryConstants.PORT, this, eventLoopGroup);
        client.settings().setHeartbeatInterval(RegistryConstants.HEARTBEAT_INTERVAL);
        client.settings().setConnectTimeout(RegistryConstants.CONNECT_TIMEOUT);
        connection = client;
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
        if (client != connection) {
            return;
        }
        LOGGER.info("Connected to primary registry at {}", client.getRemoteAddress());
        connected = true;
    }

    @Override
    public void onDisconnect(Client client) {
        // A connection already replaced by a reconnect says nothing about the current one
        if (client != connection) {
            return;
        }
        LOGGER.warn("Disconnected from primary registry at {}", client.getRemoteAddress());
        connected = false;
        archonInterface.onRegistryConnectionLost();
    }

    public boolean isConnected() {
        return connected;
    }

    /** Whether the calling thread is one of this connection's network threads, which must never block. */
    public boolean isNetworkThread() {
        return EventLoops.isCurrentThreadIn(eventLoopGroup);
    }

    /** Closes the connection without reconnecting, as after a termination. */
    public void disconnect() {
        Client client = connection;
        if (client == null) {
            return;
        }
        try {
            client.disconnect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (client != connection) {
            return;
        }
        LOGGER.debug("Received {} from primary registry", message.getClass().getSimpleName());
        if (message instanceof ApplicationRegistrationResponseMessage rankUpdateMessage) {
            this.authenticationToken = rankUpdateMessage.getAuthenticationToken();
            RegistryLookupService.getInstance().observeTerm(rankUpdateMessage.getTerm());
            archonInterface.onRankUpdateMessage(rankUpdateMessage.getRank());
        } else if (message instanceof ApplicationRankUpdate rankUpdateMessage) {
            archonInterface.onRankUpdateMessage(rankUpdateMessage.getRank());
        } else if (message instanceof ServiceLookupResponseMessage || message instanceof ServiceStartResponseMessage) {
            archonInterface.onServiceResponse(message);
        } else if (message instanceof ApplicationTerminationMessage terminationMessage) {
            LOGGER.error("Received termination message from primary registry. Reason: {}",
                    terminationMessage.getReason());
            archonInterface.onTerminated(terminationMessage.getReason());
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
