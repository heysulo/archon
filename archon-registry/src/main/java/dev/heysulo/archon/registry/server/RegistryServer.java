package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.*;
import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import dev.heysulo.archon.registry.applications.RegistryApplication;
import dev.heysulo.archon.registry.constants.ApplicationTermination;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
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

import static dev.heysulo.archon.registry.constants.Constants.APPLICATION_GROUP_NAME;
import static dev.heysulo.archon.registry.constants.Constants.APPLICATION_NAME_REGISTRY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

public class RegistryServer implements ServerCallback {
    public static Application applicationRegistry = new Application(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_FORECASTED_PRIMARY);
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
        logger.info("Client connected to registry server. IP:{}, Callback: {}", client.getRemoteAddress(), client.getCallback());
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
        logger.info("Received message from {} type {}", client.getRemoteAddress(), message.getClass().getSimpleName());
        if (message instanceof LeaderElectionMessage) {
            handleLeaderElectionMessage(client, (LeaderElectionMessage) message);
            return;
        } else if (message instanceof PrimaryRegistryLookupMessage) {
            logger.info("Received PrimaryRegistryLookupMessage from {}", client.getRemoteAddress());
            client.send(new PrimaryRegistryLookupResponseMessage(getRank()));
            return;
        }

        if (getRank() != RANK_PRIMARY) {
            logger.warn("Dropping message from {} type {}. I'm not primary", client.getRemoteAddress(), message.getClass().getSimpleName());
            return;
        }

        if (message instanceof ApplicationRegistrationMessage registrationMessage) {
            handleApplicationRegistrationMessage(client, registrationMessage);
        } else {
            logger.warn("Dropping message from {} type {}. Unhandled", client.getRemoteAddress(), message.getClass().getSimpleName());
        }
    }

    private void handleApplicationRegistrationMessage(Client client, ApplicationRegistrationMessage registrationMessage) {
        Application application = applicationManager.getApplicationInstance(registrationMessage.getGroupName(), registrationMessage.getApplicationName(), registrationMessage.getRank());
        if (application.isRunning()) {
            if (registrationMessage.getAuthenticationToken() == null) {
                logger.warn("Application {} is already running and authentication token is missing", application.getDisplayName());
                client.send(new ApplicationTerminationMessage(ApplicationTermination.REASON_TOKEN_MISSING));
                return;
            }
            if (!application.isAuthenticated(registrationMessage.getAuthenticationToken())) {
                logger.warn("Application {} is already running and authentication token is invalid", application.getDisplayName());
                client.send(new ApplicationTerminationMessage(ApplicationTermination.REASON_TOKEN_INVALID));
                return;
            }
        }
        if (registrationMessage.getRank() == 0) {
            application.handleRegistration(client);
        } else {
            application.setClient(client);
        }
        logger.info("Application Registered: {}", application.getDisplayName());
    }

    private void handleLeaderElectionMessage(Client client, LeaderElectionMessage message) {
        if (getRank() == RANK_FORECASTED_PRIMARY) {
            boolean probablePrimaryClient = Constants.APPLICATION_START_TIME.isAfter(message.getStartTime());
            logger.info("Registry at {} is {}a probable primary", message.getAddress(), probablePrimaryClient ? "" : "not ");
            if (probablePrimaryClient) {
                setRank(RANK_MIRROR);
            }
            synchronized (possibleMirrorClients) {
                logger.info("Adding client {} to possible mirror clients. Clients {}", client.getRemoteAddress(), possibleMirrorClients);
                possibleMirrorClients.add(client);
            }
        }
        LeaderStatusMessage statusMessage = new LeaderStatusMessage(getRank(), Constants.getMyAddress(), Constants.APPLICATION_START_TIME);
        client.send(statusMessage);
        if (getRank() == RANK_PRIMARY) {
            setupNewMirrorRegistry(client);
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

    public static void updateApplicationData(Client client, RegistryRegistrationResponse message) {
        setRank(message.getRank());
        applicationRegistry.setName(message.getApplicationName());
        applicationRegistry.setGroup(message.getApplicationGroup());
        applicationRegistry.setClient(client);
    }

    public static void setRank(int newRank) {
        if (applicationRegistry.getRank() == newRank) {
            return;
        }
        logger.info("Updating rank from {} to {}", applicationRegistry.getRank(), newRank);
        applicationRegistry.setRank(newRank);
    }

    public static int getRank() {
        return applicationRegistry.getRank();
    }

    public void start() {
        if (getRank() == RANK_PRIMARY) {
            logger.info("--------------- Acting as PRIMARY ---------------");
            applicationRegistry = applicationManager.getApplicationInstance(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
            applicationRegistry.setRunLevel(RunLevel.RUNNING);
            synchronized (possibleMirrorClients) {
                for (Client client : possibleMirrorClients) {
                    setupNewMirrorRegistry(client);
                }
                possibleMirrorClients.clear();
            }
        } else {
            logger.info("--------------- Acting as MIRROR ---------------");
            disconnectAllPossibleMirrorClients();
            primaryRegistry.send(new ApplicationRankUpdate(getRank()));
        }
        logger.info("Started as {}", applicationRegistry.getDisplayName());
    }

    private void setupNewMirrorRegistry(Client client) {
        Application mirrorInstance = applicationManager.getApplicationInstance(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY);
        mirrorInstance.handleRegistration(client);
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
            possibleMirrorClients.clear();
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
