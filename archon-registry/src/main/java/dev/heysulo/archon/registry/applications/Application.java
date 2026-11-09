package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.ProcessState;
import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;
import dev.heysulo.archon.registry.auth.AuthenticationManager;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.sync.ApplicationSyncMessage;
import dev.heysulo.archon.registry.messages.sync.StateSnapshotComplete;
import dev.heysulo.archon.registry.server.RegistryReadiness;
import dev.heysulo.archon.registry.server.ProcessWatchers;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.archon.registry.services.ServiceRegistry;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.ConnectionCallback;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.utils.Utils.createRegistrationResponse;
import static dev.heysulo.archon.registry.utils.Utils.createRegistryRegistrationResponse;

public class Application implements ConnectionCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);
    private static final Map<Connection, Application> connectionApplications = new HashMap<>();
    private RegistryServer registryServer = null;
    private final AuthenticationManager authenticationManager = AuthenticationManager.getInstance();
    private String group;
    private String name;
    private int rank;
    private Connection connection;
    private RunLevel runLevel;
    private String authenticationToken;
    private volatile InstanceDetails details;

    public Application(String group, String name, int rank) {
        this.name = name;
        this.rank = rank;
        this.group = group;
        this.runLevel = RunLevel.NOT_RUNNING;
    }

    public String getDisplayName() {
        return String.format("%s:%s:%d", group, name, rank);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        if (this.rank == rank) {
            return;
        }
        this.rank = rank;
        syncWithRegistryMirrors();
    }

    public Connection getConnection() {
        return connection;
    }

    public void setConnection(Connection newConnection) {
        synchronized (connectionApplications) {
            connectionApplications.remove(this.connection);
            newConnection.setCallback(this);
            connectionApplications.put(newConnection, this);
            this.connection = newConnection;
        }
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public RunLevel getRunLevel() {
        return runLevel;
    }

    public void setRunLevel(RunLevel newRunLevel) {
        if (this.runLevel == newRunLevel) {
            return;
        }
        LOGGER.info("Updating run level of {} from {} to {}", getDisplayName(), runLevel, newRunLevel);
        this.runLevel = newRunLevel;
        syncWithRegistryMirrors();
    }

    public void handleRegistration(Connection connection) {
        setConnection(connection);
        // Before the run level changes, which syncs the record to mirrors: a mirror promoted while the instance is
        // still starting must know the token it will re-register with, or it refuses it as another instance's
        this.authenticationToken = authenticationManager.generateSecureToken();
        setRunLevel(RunLevel.STARTING);
        ApplicationRegistrationResponseMessage registrationResponse = isRegistryInstance()
                ? createRegistryRegistrationResponse(this)
                : createRegistrationResponse(this);
        registrationResponse.setTerm(RegistryServer.getTerm());
        registrationResponse.setAuthenticationToken(this.authenticationToken);
        send(registrationResponse);
    }

    private boolean isRegistryInstance() {
        return this.name.equals(Constants.APPLICATION_NAME_REGISTRY)
                && this.group.equals(Constants.APPLICATION_GROUP_NAME);
    }

    public void send(Message message) {
        connection.send(message);
    }

    @Override
    public void onDisconnect(Connection connection) {
        ApplicationManager.withNamespaceLock(group, name, () -> {
            if (connection != this.connection) {
                // The instance already re-registered on a new connection, which still holds its rank and services
                return;
            }
            LOGGER.warn("{} disconnected from registry server.", this.getDisplayName());
            markGone();
        });
    }

    /**
     * Marks an instance that never reconnected to this registry as gone. After a failover the new primary holds
     * the instances' states but none of their connections; an instance that died meanwhile never disconnects from
     * it, so without this its record would stay running, and a dead leader would keep its rank forever.
     *
     * @return whether the record was retired; false if it is not running or has reconnected
     */
    public boolean retireIfUnclaimed() {
        return ApplicationManager.withNamespaceLockReturn(group, name, () -> {
            if (!isRunning() || isConnected()) {
                return false;
            }
            LOGGER.warn("{} did not reconnect after this registry became primary. Marking it gone", getDisplayName());
            markGone();
            return true;
        });
    }

    // Callers hold the namespace lock
    private void markGone() {
        ServiceRegistry.getInstance().forget(this);
        setRunLevel(RunLevel.KILLED);
        if (rank == RANK_PRIMARY) {
            ApplicationManager.getInstance().electLeader(group, name);
        }
    }

    @Override
    public void onMessage(Connection connection, Message message) {
        LOGGER.debug("Received {} from {}", message.getClass().getSimpleName(), getDisplayName());
        if (message instanceof ApplicationRankUpdate rankUpdate) {
            handleApplicationRankUpdate(rankUpdate);
        }
        if (message instanceof ApplicationSyncMessage applicationSyncMessage) {
            handleApplicationSyncMessage(applicationSyncMessage);
        }
        if (message instanceof StateSnapshotComplete && RegistryServer.getRank() != RANK_PRIMARY) {
            RegistryReadiness.ready("holding the primary's state");
        }
        if (RegistryServer.getRank() == RANK_PRIMARY) {
            handleServiceMessage(message);
        }
    }

    private void handleServiceMessage(Message message) {
        ServiceRegistry services = ServiceRegistry.getInstance();
        if (message instanceof ServiceLookupMessage lookup) {
            send(services.lookup(lookup));
        } else if (message instanceof ServiceStartedMessage started) {
            send(services.start(this, started));
        } else if (message instanceof ServiceStoppedMessage stopped) {
            services.stop(this, stopped);
        }
    }

    private void handleApplicationSyncMessage(ApplicationSyncMessage syncMessage) {
        ApplicationManager applicationManager = ApplicationManager.getInstance();
        String group = syncMessage.getGroup();
        String name = syncMessage.getName();
        int rank = syncMessage.getRank();
        Application application = applicationManager.findApplication(group, name, rank);
        if (application == null) {
            application = applicationManager.createApplication(group, name, rank);
        }
        application.setAuthenticationToken(syncMessage.getAuthenticationToken());
        application.setDetails(syncMessage.getDetails());
        application.setRunLevel(syncMessage.getRunLevel());
        LOGGER.info("Synced application {}. Run Level: {}", application.getDisplayName(), application.getRunLevel());
    }

    /**
     * The instance is ready for its rank. An acknowledgement of an older rank crossed a newer one on the wire, as
     * when the instance was promoted while finishing its last role; it is ignored, and the instance acknowledges the
     * new rank once ready for it.
     */
    private void handleApplicationRankUpdate(ApplicationRankUpdate rankUpdate) {
        if (rankUpdate.getRank() != rank) {
            LOGGER.warn("{} acknowledged rank {}, but its rank is now {}. Waiting for it to acknowledge {}",
                    getDisplayName(), rankUpdate.getRank(), rank, rank);
            return;
        }
        setRunLevel(RunLevel.RUNNING);
        if (isRegistryInstance()) {
            if (rank != RANK_PRIMARY && registryServer != null) {
                registryServer.sendStateSnapshot(this);
            }
            return;
        }
        if (rank != RANK_PRIMARY) {
            electLeaderIfNoneIsLive();
        }
    }

    /**
     * Only ready instances can be promoted. If the leader went while every other instance was still getting ready,
     * no one was promoted then; the first to become ready is now.
     */
    public void electLeaderIfNoneIsLive() {
        if (RegistryServer.inReconnectGrace()) {
            // Just after a failover the leader may simply not have reconnected yet; promoting now would take its
            // rank. The sweep at the end of the grace period retires it if it never comes back, and elects then.
            LOGGER.debug("{} is ready, but instances may still be reconnecting; leaving leadership to the sweep",
                    getDisplayName());
            return;
        }
        ApplicationManager.withNamespaceLock(group, name, () -> {
            ApplicationManager manager = ApplicationManager.getInstance();
            if (!manager.hasLivePrimary(group, name)) {
                LOGGER.info("{} has no live leader. Electing one now that {} is ready", group + "/" + name,
                        getDisplayName());
                manager.electLeader(group, name);
            }
        });
    }

    @Override
    public void onError(Connection connection, Throwable throwable) {
        LOGGER.warn("Connection error on {}", getDisplayName(), throwable);
    }

    public static Application getApplication(Connection connection) {
        synchronized (connectionApplications) {
            return connectionApplications.get(connection);
        }
    }

    public boolean promote() {
        if (runLevel != RunLevel.RUNNING || !isConnected()) {
            LOGGER.warn("Cannot promote {} — not running or disconnected (runLevel={}, connected={})",
                    getDisplayName(), runLevel, isConnected());
            return false;
        }
        LOGGER.info("Promoting application {} to Primary", getDisplayName());
        setRank(RANK_PRIMARY);
        setRunLevel(RunLevel.CHANGING);
        try {
            send(new ApplicationRankUpdate(RANK_PRIMARY));
        } catch (Exception e) {
            LOGGER.error("Failed to send promotion to {}. Reverting.", getDisplayName(), e);
            setRunLevel(RunLevel.KILLED);
            return false;
        }
        return true;
    }

    /** Where the instance runs, as it reported when it last registered. */
    public InstanceDetails getDetails() {
        return details;
    }

    public void setDetails(InstanceDetails details) {
        this.details = details;
    }

    public String getAuthenticationToken() {
        return authenticationToken;
    }

    public void setAuthenticationToken(String authenticationToken) {
        this.authenticationToken = authenticationToken;
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    public boolean isRunning() {
        return runLevel.getProcessState() == ProcessState.RUNNING;
    }

    // Constant time, since callers can probe it over the network through re-registration and verification
    public boolean isAuthenticated(String authenticationToken) {
        if (this.authenticationToken == null || authenticationToken == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                this.authenticationToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                authenticationToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void setRegistryServer(RegistryServer registryServer) {
        this.registryServer = registryServer;
    }

    private void syncWithRegistryMirrors() {
        // Every rank and run level change passes through here, so watchers hear about all of them
        ProcessWatchers.getInstance().changed();
        if (registryServer == null) {
            LOGGER.warn("Registry server is not initialized. Cannot sync {} with registry mirrors", getDisplayName());
            return;
        }
        if (RegistryServer.getRank() != RANK_PRIMARY) {
            return;
        }
        ApplicationSyncMessage message = new ApplicationSyncMessage(this);
        registryServer.getActiveMirrorApplications().forEach(app -> {
            LOGGER.debug("Syncing {} with {}", getDisplayName(), app.getDisplayName());
            app.send(message);
        });
    }
}
