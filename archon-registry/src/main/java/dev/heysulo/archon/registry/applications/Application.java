package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.ProcessState;
import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.registry.auth.AuthenticationManager;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.sync.ApplicationSyncMessage;
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
        setRunLevel(RunLevel.STARTING);
        ApplicationRegistrationResponseMessage registrationResponse = isRegistryInstance()
                ? createRegistryRegistrationResponse(this)
                : createRegistrationResponse(this);
        registrationResponse.setTerm(RegistryServer.getTerm());
        this.authenticationToken = authenticationManager.generateSecureToken();
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
            ServiceRegistry.getInstance().forget(this);
            setRunLevel(RunLevel.KILLED);
            if (rank == RANK_PRIMARY) {
                ApplicationManager.getInstance().electLeader(group, name);
            }
        });
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
        application.setRunLevel(syncMessage.getRunLevel());
        LOGGER.info("Synced application {}. Run Level: {}", application.getDisplayName(), application.getRunLevel());
    }

    private void handleApplicationRankUpdate(ApplicationRankUpdate rankUpdate) {
        if (rankUpdate.getRank() != rank) {
            askToDie(String.format("Rank confirmation mismatch. Expected %d, got %d", getRank(), rankUpdate.getRank()));
            return;
        }
        setRunLevel(RunLevel.RUNNING);
        if (isRegistryInstance() && rank != RANK_PRIMARY && registryServer != null) {
            registryServer.sendStateSnapshot(this);
        }
    }

    private void askToDie(String reason) {
        LOGGER.error("Asking {} to die. Reason: {}", getDisplayName(), reason);
        try {
            connection.disconnect();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        setRunLevel(RunLevel.TERMINATED);
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

    public boolean isAuthenticated(String authenticationToken) {
        return this.authenticationToken.equals(authenticationToken);
    }

    public void setRegistryServer(RegistryServer registryServer) {
        this.registryServer = registryServer;
    }

    private void syncWithRegistryMirrors() {
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
