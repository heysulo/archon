package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.*;
import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import dev.heysulo.archon.registry.applications.RegistryApplication;
import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.constants.ApplicationTermination;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import dev.heysulo.archon.registry.messages.sync.ApplicationSyncMessage;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.BasicServer;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.heysulo.archon.registry.constants.Constants.APPLICATION_GROUP_NAME;
import static dev.heysulo.archon.registry.constants.Constants.APPLICATION_NAME_REGISTRY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_FAILOVER_CONNECTION_ATTEMPTS;

public class RegistryServer implements ServerCallback {
    public static Application applicationRegistry = new Application(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_FORECASTED_PRIMARY);
    private static RegistryServer instance;
    private static final Logger logger = LoggerFactory.getLogger(RegistryServer.class);
    private static Server registryServer;
    private static final RegistryApplication primaryRegistry = new RegistryApplication(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
    private final EventLoopGroup workerGroup;
    private final EventLoopGroup bossGroup;
    private final AtomicBoolean failoverInProgress = new AtomicBoolean(false);
    private final ExecutorService failoverExecutor = Executors.newSingleThreadExecutor(
            new DefaultThreadFactory("registry-failover", true));
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
    public void onConnect(Server server, Connection client) {
        logger.info("Client connected to registry server. IP:{}", client.getRemoteAddress());
    }

    @Override
    public void onDisconnect(Server server, Connection client) {
        logger.info("Registry Server Client disconnected from {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Server server, Connection client, Message message) {
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

        // Handled as Primary
        if (message instanceof ApplicationRegistrationMessage registrationMessage) {
            handleApplicationRegistrationMessage(client, registrationMessage);
        } else {
            logger.warn("Dropping message from {} type {}. Unhandled", client.getRemoteAddress(), message.getClass().getSimpleName());
        }
    }

    private void handleApplicationRegistrationMessage(Connection client, ApplicationRegistrationMessage registrationMessage) {
        if (registrationMessage.getRank() == RANK_UNKNOWN) {
            registerNewApplicationInstance(registrationMessage.getGroupName(), registrationMessage.getApplicationName(), client);
        } else {
            reRegisterExistingApplicationInstanceNg(
                    registrationMessage.getGroupName(), registrationMessage.getApplicationName(),
                    registrationMessage.getRank(), registrationMessage.getAuthenticationToken(), client);
        }
    }

    private void registerNewApplicationInstance(String group, String name, Connection client) {
        Application application = applicationManager.createApplicationWithNextAvailableRank(group, name);
        application.handleRegistration(client);
    }

    private void reRegisterExistingApplicationInstanceNg(String group, String name, int rank, String authenticationToken, Connection client) {
        Application application = applicationManager.findApplication(group, name, rank);
        if (application == null) {
            logger.error("Application {}:{} from {} is attempting to register with a rank which is not provided by the registry. Rank: {}",
                    group, name, client.getRemoteAddress(), rank);
            client.send(new ApplicationTerminationMessage(ApplicationTermination.REASON_RANK_UNAVAILABLE_FOR_RE_REGISTRATION));
            return;
        }

        if (application.isRunning() && !validateAuthenticationToken(application, authenticationToken, client)) {
            return;
        }

        application.setClient(client);
        logger.info("Application {} re-registered with client: {}", application.getDisplayName(), client.getRemoteAddress());
    }

    private boolean validateAuthenticationToken(Application application, String authenticationToken, Connection client) {
        if (authenticationToken == null) {
            logger.warn("Cannot re-register application {} without authentication token. Client Address: {}",
                    application.getDisplayName(), client.getRemoteAddress());
            client.send(new ApplicationTerminationMessage(ApplicationTermination.REASON_TOKEN_MISSING));
            return false;
        }
        if (!application.isAuthenticated(authenticationToken)) {
            logger.warn("Cannot re-register application {}. Invalid authentication token. Client Address: {}",
                    application.getDisplayName(), client.getRemoteAddress());
            client.send(new ApplicationTerminationMessage(ApplicationTermination.REASON_TOKEN_INVALID));
            return false;
        }
        return true;
    }

    private void handleLeaderElectionMessage(Connection client, LeaderElectionMessage message) {
        client.send(new LeaderStatusMessage(getRank(), Constants.getMyAddress(), Constants.APPLICATION_START_TIME));
        if (getRank() == RANK_PRIMARY && Application.getApplication(client) == null) {
            setupMirrorRegistry(client, message.getRank());
        }
    }

    @Override
    public void onError(Server server, Connection client, Throwable throwable) {
        logger.error("Registry error", throwable);
    }

    public void initialize() {
        if (registryServer != null) {
            throw new IllegalStateException("Registry server is already started");
        }
        logger.info("Starting registry server on port {}", Constants.REGISTRY_PORT);
        registryServer = new BasicServer(Constants.REGISTRY_PORT, this, bossGroup, workerGroup);
        registryServer.settings().addTrustedPackage("dev.heysulo.archon.**");
        Constants.TRUSTED_TIME_CLASSES.forEach(registryServer.settings()::addTrustedPackage);
        registryServer.settings().setHeartbeatInterval(Constants.REGISTRY_HEARTBEAT_INTERVAL);
        try {
            registryServer.start();
            logger.info("Registry server started successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Failed to start registry server", e);
            throw new RuntimeException("Failed to start registry server", e);
        }
    }

    public static void updateApplicationData(Connection client, RegistryRegistrationResponse message) {
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
            actAsPrimary();
        } else {
            logger.info("--------------- Acting as MIRROR ---------------");
            primaryRegistry.send(new ApplicationRankUpdate(getRank()));
        }
        logger.info("Started as {}", applicationRegistry.getDisplayName());
    }

    private void actAsPrimary() {
        logger.info("--------------- Acting as PRIMARY ---------------");
        applicationRegistry = applicationManager.findApplication(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
        if (applicationRegistry == null) {
            applicationRegistry = applicationManager.createApplication(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
        }
        applicationRegistry.setRunLevel(RunLevel.RUNNING);
        retireDisconnectedMirrorRecords();
    }

    // A promoted mirror inherits records of peers it has no connection to. They rejoin through election.
    private void retireDisconnectedMirrorRecords() {
        applicationManager.findAllApplications(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY)
                .stream()
                .filter(app -> app.getRank() != RANK_PRIMARY)
                .filter(app -> !app.isConnected())
                .toList()
                .forEach(app -> app.setRunLevel(RunLevel.KILLED));
    }

    private void setupMirrorRegistry(Connection client, int previousRank) {
        Application mirrorInstance = applicationManager.findApplication(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, previousRank);
        if (previousRank < RANK_MIRROR || mirrorInstance == null || mirrorInstance.isConnected()) {
            mirrorInstance = applicationManager.createApplicationWithNextAvailableRank(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY);
        }
        mirrorInstance.handleRegistration(client);
    }

    /**
     * Brings a new mirror up to date. Mirrors only receive changes after they join, so without this a mirror that
     * restarted would lack every application registered before it, and could lose them if promoted.
     */
    public void sendStateSnapshot(Application mirror) {
        applicationManager.findAllKnownApplications()
                .forEach(application -> mirror.send(new ApplicationSyncMessage(application)));
        logger.info("Sent state snapshot to {}", mirror.getDisplayName());
    }

    /**
     * Starts an election after losing the primary. It runs on its own thread because an election blocks, and the
     * caller is a Netty event loop that may be serving the connections the election depends on.
     */
    public void handlePrimaryLost() {
        if (!failoverInProgress.compareAndSet(false, true)) {
            return;
        }
        failoverExecutor.execute(() -> {
            try {
                LeaderElectorClient.getInstance().start(REGISTRY_FAILOVER_CONNECTION_ATTEMPTS);
                start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Failover interrupted", e);
            } catch (RuntimeException e) {
                logger.error("Failover failed", e);
            } finally {
                failoverInProgress.set(false);
            }
        });
    }

    public List<Application> getActiveMirrorApplications() {
        return applicationManager.findAllApplications(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY)
                .stream()
                .filter(app -> app.getRank() != RANK_PRIMARY)
                .filter(app -> app.getRunLevel() == RunLevel.RUNNING)
                .filter(Application::isConnected)
                .toList();
    }

    public static void setPrimaryConnection(Connection newPrimaryClient) {
        Connection currentPrimaryClient = primaryRegistry.getClient();
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
