package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.registry.client.PrimaryRegistryClient;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.RankUpdateMessage;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.BasicServer;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static dev.heysulo.archon.registry.constants.Constants.*;

public class RegistryServer implements ServerCallback {
    public static int rank;
    private static RegistryServer instance;
    private static final Logger logger = LoggerFactory.getLogger(RegistryServer.class);
    private static final ClientCallback primaryClientCallback = new PrimaryRegistryClient();
    private static Server registryServer;
    private static Client primaryClient;
    private final EventLoopGroup workerGroup;
    private final EventLoopGroup bossGroup;
    private final List<Client> mirrorClients = Collections.synchronizedList(new ArrayList<>());

    public static RegistryServer buildInstance(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        if (instance == null) {
            synchronized (RegistryServer.class) {
                instance = new RegistryServer(bossGroup, workerGroup);
            }
        }
        return instance;
    }

    public static RegistryServer getInstance() {
        return instance;
    }

    private RegistryServer(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        setRank(RANK_FORECASTED_PRIMARY);
    }

    @Override
    public void OnConnect(Server server, Client client) {

    }

    @Override
    public void OnDisconnect(Server server, Client client) {
        logger.info("Registry Server Client disconnected from {}", client.getRemoteAddress());
        synchronized (mirrorClients) {
            mirrorClients.remove(client);
        }
    }

    @Override
    public void OnMessage(Server server, Client client, Message message) {
        if (message instanceof LeaderElectionMessage) {
            handleLeaderElectionMessage(client, (LeaderElectionMessage) message);
        } else {
            logger.error("Message is not a LeaderElectionMessage");
        }
    }

    private void handleLeaderElectionMessage(Client client, LeaderElectionMessage message) {
        if (rank == RANK_FORECASTED_PRIMARY) {
            boolean probablePrimaryClient = Constants.APPLICATION_START_TIME.isAfter(message.getStartTime());
            logger.info("Registry at {} is {}a probable primary", message.getAddress(), probablePrimaryClient ? "" : "not ");
            if (probablePrimaryClient) {
                setRank(RANK_MIRROR);
            }
        }
        LeaderStatusMessage statusMessage = new LeaderStatusMessage(rank, Constants.getMyAddress(), Constants.APPLICATION_START_TIME);
        client.send(statusMessage);
        if (rank == RANK_PRIMARY) {
            client.send(new RankUpdateMessage(99)); // FIX THIS
        }
        synchronized (mirrorClients) {
            mirrorClients.add(client);
        }
    }

    @Override
    public void OnError(Server server, Client client, Throwable throwable) {
        logger.error("Registry error", throwable);
    }

    public void initialize() {
        if (registryServer != null) {
            throw new IllegalStateException("Registry server is already started");
        }
        logger.info("Starting registry server on port {}", Constants.REGISTRY_PORT);
        registryServer = new BasicServer(Constants.REGISTRY_PORT, this, bossGroup, workerGroup);
        registryServer.addTrustedPackage("dev.heysulo.archon.**");
        try {
            registryServer.start();
            logger.info("Registry server started successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Failed to start registry server", e);
            throw new RuntimeException("Failed to start registry server", e);
        }
    }

    public void stop() {
        if (registryServer != null) {
            logger.info("Stopping registry server");
            try {
                registryServer.stop();
            } catch (InterruptedException e) {
                logger.error("Failed to stop registry server", e);
            }
        }
    }

    public static void setRank(int newRank) {
        if (rank == newRank) {
            return;
        }
        logger.info("Updating rank from {} to {}", rank, newRank);
        RegistryServer.rank = newRank;
    }

    public static int getRank() {
        return rank;
    }

    public void start() {
        if (rank == RANK_PRIMARY) {
            int mirrorRank = 2;
            synchronized (mirrorClients) {
                for (Client client : mirrorClients) {
                    client.send(new RankUpdateMessage(mirrorRank++));
                }
            }
        } else {
            disconnectAllMirrorClients();
        }
    }

    private void disconnectAllMirrorClients() {
        synchronized (mirrorClients) {
            mirrorClients.forEach(client -> {
                try {
                    client.disconnect();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void setPrimaryConnection(Client newPrimaryClient) {
        if (newPrimaryClient.equals(primaryClient)) {
            return;
        }
        logger.info("Updating primary address from {} to {}", primaryClient != null ? primaryClient.getRemoteAddress() : "null", newPrimaryClient.getRemoteAddress());
        RegistryServer.primaryClient = newPrimaryClient;
        primaryClient.setCallback(primaryClientCallback);
    }
}
