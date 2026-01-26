package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import dev.heysulo.archon.registry.applications.RegistryApplication;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.application.ApplicationRegistrationMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.BasicServer;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static dev.heysulo.archon.registry.constants.Constants.*;

public class RegistryServer implements ServerCallback {
    public static Application application = new Application(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_FORECASTED_PRIMARY);
    private static RegistryServer instance;
    private static final Logger logger = LoggerFactory.getLogger(RegistryServer.class);
    private static Server registryServer;
    private static final RegistryApplication primaryRegistry = new RegistryApplication(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
    private final EventLoopGroup workerGroup;
    private final EventLoopGroup bossGroup;
    private final List<Client> possibleMirrorClients = Collections.synchronizedList(new ArrayList<>());
    private final ApplicationManager applicationManager = ApplicationManager.getInstance();

    public static RegistryServer getInstance() {
        if (instance == null) {
            synchronized (RegistryServer.class) {
                instance = new RegistryServer();
            }
        }
        return instance;
    }

    private RegistryServer() {
        this.bossGroup = new NioEventLoopGroup(new DefaultThreadFactory("registry-server-boss-group"));
        this.workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("registry-server-worker-group"));
        setRank(RANK_FORECASTED_PRIMARY);
    }

    @Override
    public void OnConnect(Server server, Client client) {

    }

    @Override
    public void OnDisconnect(Server server, Client client) {
        logger.info("Registry Server Client disconnected from {}", client.getRemoteAddress());
        synchronized (possibleMirrorClients) {
            possibleMirrorClients.remove(client);
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
        if (getRank() == RANK_FORECASTED_PRIMARY) {
            boolean probablePrimaryClient = Constants.APPLICATION_START_TIME.isAfter(message.getStartTime());
            logger.info("Registry at {} is {}a probable primary", message.getAddress(), probablePrimaryClient ? "" : "not ");
            if (probablePrimaryClient) {
                setRank(RANK_MIRROR);
            }
        }
        LeaderStatusMessage statusMessage = new LeaderStatusMessage(getRank(), Constants.getMyAddress(), Constants.APPLICATION_START_TIME);
        client.send(statusMessage);
        if (getRank() == RANK_PRIMARY) {
            Application instance = applicationManager.getApplicationInstance(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY);
            client.send(new ApplicationRegistrationMessage(instance));
        }
        synchronized (possibleMirrorClients) {
            possibleMirrorClients.add(client);
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

    public static void updateApplicationData(Client client, ApplicationRegistrationMessage message) {
        setRank(message.getRank());
        application.setName(message.getApplicationName());
        application.setGroup(message.getApplicationGroup());
        application.setClient(client);
    }

    public static void setRank(int newRank) {
        if (application.getRank() == newRank) {
            return;
        }
        logger.info("Updating rank from {} to {}", application.getRank(), newRank);
        application.setRank(newRank);
    }

    public static int getRank() {
        return application.getRank();
    }

    public void start() {
        if (getRank() == RANK_PRIMARY) {
            logger.info("--------------- Acting as PRIMARY ---------------");
            application = applicationManager.getApplicationInstance(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
            synchronized (possibleMirrorClients) {
                for (Client client : possibleMirrorClients) {
                    Application instance = applicationManager.getApplicationInstance(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY);
                    instance.setClient(client);
                    instance.send(new ApplicationRegistrationMessage(instance));
                }
                possibleMirrorClients.clear();
            }
        } else {
            logger.info("--------------- Acting as MIRROR ---------------");
            disconnectAllPossibleMirrorClients();
        }
        logger.info("Started as {}", application.getDisplayName());
    }

    private void disconnectAllPossibleMirrorClients() {
        synchronized (possibleMirrorClients) {
            possibleMirrorClients.forEach(client -> {
                try {
                    client.disconnect();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void setPrimaryConnection(Client newPrimaryClient) {
        Client currentPrimaryClient = primaryRegistry.getClient();
        if (newPrimaryClient.equals(currentPrimaryClient)) {
            return;
        }
        logger.info("Updating primary address from {} to {}", currentPrimaryClient != null ? currentPrimaryClient.getRemoteAddress() : "null", newPrimaryClient.getRemoteAddress());
        primaryRegistry.setClient(newPrimaryClient);
    }

    public void stop() throws InterruptedException {
        bossGroup.shutdownGracefully().sync();
        workerGroup.shutdownGracefully().sync();
    }
}
