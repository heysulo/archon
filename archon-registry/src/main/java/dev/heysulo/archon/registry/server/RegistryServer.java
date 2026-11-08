package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationTerminationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import dev.heysulo.archon.registry.applications.RegistryApplication;
import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.constants.ApplicationTermination;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.PrimaryStanding;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import dev.heysulo.archon.registry.messages.leaderelection.VoteRequest;
import dev.heysulo.archon.registry.messages.leaderelection.VoteResponse;
import dev.heysulo.archon.registry.messages.sync.ApplicationSyncMessage;
import dev.heysulo.archon.registry.services.ServiceRegistry;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.heysulo.archon.registry.constants.Constants.APPLICATION_GROUP_NAME;
import static dev.heysulo.archon.registry.constants.Constants.APPLICATION_NAME_REGISTRY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_FAILOVER_CONNECTION_ATTEMPTS;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LOSER_EXIT_DELAY;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_PRIMARY_SCAN_INTERVAL;

public class RegistryServer implements ServerCallback {
    private static Application applicationRegistry = new Application(APPLICATION_GROUP_NAME,
            APPLICATION_NAME_REGISTRY, RANK_FORECASTED_PRIMARY);
    private static RegistryServer instance;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryServer.class);
    private Server server;
    private static final RegistryApplication primaryRegistry = new RegistryApplication(APPLICATION_GROUP_NAME,
            APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
    private final EventLoopGroup workerGroup;
    private final EventLoopGroup bossGroup;
    private final AtomicBoolean failoverInProgress = new AtomicBoolean(false);
    private final ExecutorService failoverExecutor = Executors.newSingleThreadExecutor(
            new DefaultThreadFactory("registry-failover", true));
    private final ScheduledExecutorService primaryScanExecutor = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("registry-primary-scan", true));
    private final AtomicBoolean lostToRival = new AtomicBoolean(false);
    private final ApplicationManager applicationManager = ApplicationManager.getInstance();
    // The highest leadership term this registry knows was won. Only a won election raises it.
    private static volatile long term = 0;

    public static synchronized RegistryServer getInstance() {
        if (instance == null) {
            instance = new RegistryServer();
        }
        return instance;
    }

    private RegistryServer() {
        this.bossGroup = new NioEventLoopGroup(new DefaultThreadFactory("registry-server-boss-group"));
        this.workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("registry-server-worker-group"));
        setRank(RANK_FORECASTED_PRIMARY);
    }

    @Override
    public void onConnect(Server server, Connection connection) {
        LOGGER.info("Client connected to registry server. IP:{}", connection.getRemoteAddress());
    }

    @Override
    public void onDisconnect(Server server, Connection connection) {
        LOGGER.info("Registry Server Client disconnected from {}", connection.getRemoteAddress());
    }

    @Override
    public void onMessage(Server server, Connection connection, Message message) {
        LOGGER.info("Received message from {} type {}", connection.getRemoteAddress(),
                message.getClass().getSimpleName());
        if (message instanceof VoteRequest voteRequest) {
            handleVoteRequest(connection, voteRequest);
            return;
        } else if (message instanceof PrimaryStanding rival) {
            handleRivalStanding(connection, rival);
            return;
        } else if (message instanceof LeaderElectionMessage) {
            handleLeaderElectionMessage(connection, (LeaderElectionMessage) message);
            return;
        } else if (message instanceof PrimaryRegistryLookupMessage) {
            LOGGER.info("Received PrimaryRegistryLookupMessage from {}", connection.getRemoteAddress());
            PrimaryRegistryLookupResponseMessage response = new PrimaryRegistryLookupResponseMessage(getRank());
            response.setTerm(getTerm());
            connection.send(response);
            return;
        }

        if (getRank() != RANK_PRIMARY) {
            LOGGER.warn("Dropping message from {} type {}. I'm not primary", connection.getRemoteAddress(),
                    message.getClass().getSimpleName());
            return;
        }

        // Handled as Primary
        if (message instanceof ApplicationRegistrationMessage registrationMessage) {
            handleApplicationRegistrationMessage(connection, registrationMessage);
        } else if (message instanceof ServiceLookupMessage lookup) {
            // Lookups need no registration, so tools and unregistered clients can find services too
            connection.send(ServiceRegistry.getInstance().lookup(lookup));
        } else {
            LOGGER.warn("Dropping message from {} type {}. Unhandled", connection.getRemoteAddress(),
                    message.getClass().getSimpleName());
        }
    }

    private void handleApplicationRegistrationMessage(Connection connection,
            ApplicationRegistrationMessage registrationMessage) {
        if (registrationMessage.getRank() == RANK_UNKNOWN) {
            registerNewApplicationInstance(registrationMessage.getGroupName(),
                    registrationMessage.getApplicationName(), connection);
        } else {
            reRegisterApplicationInstance(
                    registrationMessage.getGroupName(), registrationMessage.getApplicationName(),
                    registrationMessage.getRank(), registrationMessage.getAuthenticationToken(), connection);
        }
    }

    // Registration and re-registration share the namespace lock, so a rank cannot be handed out while an instance
    // is reclaiming it.
    private void registerNewApplicationInstance(String group, String name, Connection connection) {
        ApplicationManager.withNamespaceLock(group, name, () -> {
            Application application = applicationManager.createApplicationWithNextAvailableRank(group, name);
            application.handleRegistration(connection);
        });
    }

    private void reRegisterApplicationInstance(String group, String name, int rank, String authenticationToken,
            Connection connection) {
        ApplicationManager.withNamespaceLock(group, name, () -> {
            Application application = applicationManager.findApplication(group, name, rank);
            if (application == null) {
                LOGGER.error("Application {}:{} from {} is re-registering with a rank the registry never provided. "
                        + "Rank: {}", group, name, connection.getRemoteAddress(), rank);
                askToTerminate(connection, ApplicationTermination.REASON_RANK_UNAVAILABLE_FOR_RE_REGISTRATION);
                return;
            }
            if (isHeldByAnotherInstance(application, authenticationToken)) {
                LOGGER.warn("Rank of {} is held by another running instance. Asking {} to terminate",
                        application.getDisplayName(), connection.getRemoteAddress());
                askToTerminate(connection, ApplicationTermination.REASON_RANK_HELD_BY_ANOTHER_INSTANCE);
                return;
            }
            application.setConnection(connection);
            application.setRunLevel(RunLevel.RUNNING);
            LOGGER.info("Application {} re-registered with client: {}", application.getDisplayName(),
                    connection.getRemoteAddress());
        });
    }

    /**
     * Whether a different instance owns this rank. Each registration issues a new token, so a running record whose
     * token differs from the reconnecting instance's belongs to someone else. A record that is no longer running is
     * free for its previous owner to reclaim.
     */
    private static boolean isHeldByAnotherInstance(Application application, String authenticationToken) {
        return application.isRunning()
                && (authenticationToken == null || !application.isAuthenticated(authenticationToken));
    }

    private static void askToTerminate(Connection connection, String reason) {
        connection.send(new ApplicationTerminationMessage(reason));
    }

    private void handleVoteRequest(Connection connection, VoteRequest request) {
        // A primary never backs a rival. Two primaries are settled by comparing standings once they meet.
        boolean granted = getRank() != RANK_PRIMARY
                && LeaderElectorClient.getInstance().considerVote(request, isPrimaryKnown());
        LOGGER.info("{} vote for {} in term {}", granted ? "Granted" : "Refused", request.getAddress(),
                request.getTerm());
        connection.send(new VoteResponse(request.getTerm(), getTerm(), granted));
    }

    private static boolean isPrimaryKnown() {
        return primaryRegistry.isConnected();
    }

    private void handleRivalStanding(Connection connection, PrimaryStanding rival) {
        if (getRank() != RANK_PRIMARY || rival.isReply()) {
            return;
        }
        PrimaryStanding mine = currentStanding(true);
        connection.send(mine);
        resolveRivalPrimary(mine, rival);
    }

    private void handleRivalReply(PrimaryStanding rival) {
        if (getRank() == RANK_PRIMARY) {
            resolveRivalPrimary(currentStanding(false), rival);
        }
    }

    PrimaryStanding currentStanding(boolean reply) {
        return new PrimaryStanding(Constants.getMyAddress(), Constants.APPLICATION_START_TIME, getTerm(),
                countSupporters(), reply);
    }

    /**
     * Keeps exactly one of two primaries. The loser terminates, and its applications re-register with the winner.
     * The winner moves past the loser's term, so applications that followed the loser accept it.
     */
    synchronized void resolveRivalPrimary(PrimaryStanding mine, PrimaryStanding rival) {
        if (rival.beats(mine)) {
            if (lostToRival.compareAndSet(false, true)) {
                String reason = String.format("Lost the vote to primary %s. This primary had %s", rival, mine);
                LOGGER.error("{}. Terminating in {} ms", reason, REGISTRY_LOSER_EXIT_DELAY.toMillis());
                // The delay lets this standing reach the winner, which may need it to move past this term
                primaryScanExecutor.schedule(() -> RegistryTermination.terminate(reason),
                        REGISTRY_LOSER_EXIT_DELAY.toMillis(), TimeUnit.MILLISECONDS);
            }
            return;
        }
        LOGGER.warn("Won the vote against primary {}. This primary has {}", rival, mine);
        if (rival.getTerm() >= getTerm()) {
            setTerm(rival.getTerm() + 1);
        }
    }

    private void handleLeaderElectionMessage(Connection connection, LeaderElectionMessage message) {
        connection.send(new LeaderStatusMessage(getRank(), getTerm(), Constants.getMyAddress(),
                Constants.APPLICATION_START_TIME));
        if (getRank() != RANK_PRIMARY) {
            observeTerm(message.getTerm());
            return;
        }
        if (message.getTerm() > getTerm()) {
            // Another primary held that term and is gone or will lose the vote. Applications that followed it
            // ignore primaries with an older term, so this one moves past it.
            LOGGER.warn("{} knows of term {}, newer than this primary's term {}", message.getAddress(),
                    message.getTerm(), getTerm());
            setTerm(message.getTerm() + 1);
        }
        // Mirrors joining at the same time arrive on different event loops; the lock keeps their ranks distinct.
        ApplicationManager.withNamespaceLock(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, () -> {
            if (Application.getApplication(connection) == null) {
                setupMirrorRegistry(connection, message.getRank());
            }
        });
    }

    @Override
    public void onError(Server server, Connection connection, Throwable throwable) {
        LOGGER.error("Registry error", throwable);
    }

    public void initialize() {
        if (server != null) {
            throw new IllegalStateException("Registry server is already started");
        }
        LOGGER.info("Starting registry server on port {}", Constants.REGISTRY_PORT);
        server = new BasicServer(Constants.REGISTRY_PORT, this, bossGroup, workerGroup);
        server.settings().addTrustedPackage("dev.heysulo.archon.**");
        Constants.TRUSTED_TIME_CLASSES.forEach(server.settings()::addTrustedPackage);
        server.settings().setHeartbeatInterval(Constants.REGISTRY_HEARTBEAT_INTERVAL);
        try {
            server.start();
            LOGGER.info("Registry server started successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Failed to start registry server", e);
            throw new RuntimeException("Failed to start registry server", e);
        }
    }

    public static void updateApplicationData(Connection connection, RegistryRegistrationResponse message) {
        setRank(message.getRank());
        applicationRegistry.setName(message.getApplicationName());
        applicationRegistry.setGroup(message.getApplicationGroup());
        applicationRegistry.setConnection(connection);
    }

    public static void setRank(int newRank) {
        if (applicationRegistry.getRank() == newRank) {
            return;
        }
        LOGGER.info("Updating rank from {} to {}", applicationRegistry.getRank(), newRank);
        applicationRegistry.setRank(newRank);
    }

    public static long getTerm() {
        return term;
    }

    public static synchronized void setTerm(long newTerm) {
        LOGGER.info("Updating term from {} to {}", term, newTerm);
        term = newTerm;
    }

    /** Records a term won elsewhere, keeping the highest one seen. */
    public static synchronized void observeTerm(long seenTerm) {
        if (seenTerm > term) {
            setTerm(seenTerm);
        }
    }

    public static int getRank() {
        return applicationRegistry.getRank();
    }

    public static Application getApplicationRegistry() {
        return applicationRegistry;
    }

    public void start() {
        if (getRank() == RANK_PRIMARY) {
            actAsPrimary();
        } else {
            LOGGER.info("--------------- Acting as MIRROR ---------------");
            primaryRegistry.send(new ApplicationRankUpdate(getRank()));
        }
        LOGGER.info("Started as {}", applicationRegistry.getDisplayName());
    }

    private void actAsPrimary() {
        LOGGER.info("--------------- Acting as PRIMARY ---------------");
        applicationRegistry = findRegistryInstance(RANK_PRIMARY);
        if (applicationRegistry == null) {
            applicationRegistry = applicationManager.createApplication(
                    APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_PRIMARY);
        }
        applicationRegistry.setRunLevel(RunLevel.RUNNING);
        retireDisconnectedMirrorRecords();
        scanForOtherPrimaries();
    }

    // A primary that loses its mirrors keeps serving. If it was partitioned off, the other side may elect its own
    // primary, so it keeps looking for the other registries and settles any rival by vote once they meet.
    private void scanForOtherPrimaries() {
        PrimaryScanner scanner = new PrimaryScanner(() -> currentStanding(false), this::handleRivalReply);
        long intervalMillis = REGISTRY_PRIMARY_SCAN_INTERVAL.toMillis();
        primaryScanExecutor.scheduleWithFixedDelay(scanner::scan, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // Every mirror backs the primary it follows, so these are this primary's votes, its own included
    private int countSupporters() {
        long connectedMirrors = findRegistryInstances()
                .stream()
                .filter(app -> app.getRank() != RANK_PRIMARY)
                .filter(Application::isRunning)
                .filter(Application::isConnected)
                .count();
        return (int) connectedMirrors + 1;
    }

    // A promoted mirror inherits records of peers it has no connection to. They rejoin through election.
    private void retireDisconnectedMirrorRecords() {
        findRegistryInstances()
                .stream()
                .filter(app -> app.getRank() != RANK_PRIMARY)
                .filter(app -> !app.isConnected())
                .toList()
                .forEach(app -> app.setRunLevel(RunLevel.KILLED));
    }

    private void setupMirrorRegistry(Connection connection, int previousRank) {
        Application mirrorInstance = findRegistryInstance(previousRank);
        if (previousRank < RANK_MIRROR || mirrorInstance == null || mirrorInstance.isConnected()) {
            // Rank 1 belongs to this primary even before actAsPrimary records it.
            mirrorInstance = applicationManager.createApplicationWithNextAvailableRank(
                    APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, RANK_MIRROR);
        }
        mirrorInstance.handleRegistration(connection);
    }

    /**
     * Brings a new mirror up to date. Mirrors only receive changes after they join, so without this a mirror that
     * restarted would lack every application registered before it, and could lose them if promoted.
     */
    public void sendStateSnapshot(Application mirror) {
        applicationManager.findAllKnownApplications()
                .forEach(application -> mirror.send(new ApplicationSyncMessage(application)));
        LOGGER.info("Sent state snapshot to {}", mirror.getDisplayName());
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
                LOGGER.error("Failover interrupted", e);
            } catch (RuntimeException e) {
                LOGGER.error("Failover failed", e);
            } finally {
                failoverInProgress.set(false);
            }
        });
    }

    public List<Application> getActiveMirrorApplications() {
        return findRegistryInstances()
                .stream()
                .filter(app -> app.getRank() != RANK_PRIMARY)
                .filter(app -> app.getRunLevel() == RunLevel.RUNNING)
                .filter(Application::isConnected)
                .toList();
    }

    public static void setPrimaryConnection(Connection newPrimaryConnection) {
        Connection currentPrimaryConnection = primaryRegistry.getConnection();
        if (newPrimaryConnection.equals(currentPrimaryConnection)) {
            return;
        }
        String currentAddress = currentPrimaryConnection != null ? currentPrimaryConnection.getRemoteAddress() : null;
        LOGGER.info("Updating primary address from {} to {}", currentAddress, newPrimaryConnection.getRemoteAddress());
        primaryRegistry.setConnection(newPrimaryConnection);
    }

    private Application findRegistryInstance(int rank) {
        return applicationManager.findApplication(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY, rank);
    }

    private List<Application> findRegistryInstances() {
        return applicationManager.findAllApplications(APPLICATION_GROUP_NAME, APPLICATION_NAME_REGISTRY);
    }

    public void stop() throws InterruptedException {
        bossGroup.shutdownGracefully().sync();
        workerGroup.shutdownGracefully().sync();
    }
}
