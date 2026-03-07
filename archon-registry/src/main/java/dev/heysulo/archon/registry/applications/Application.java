package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.ProcessState;
import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.AuthenticatedMessage;
import dev.heysulo.archon.registry.auth.AuthenticationManager;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.sync.ApplicationSyncMessage;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.utils.Utils.createRegistrationResponse;
import static dev.heysulo.archon.registry.utils.Utils.createRegistryRegistrationResponse;

public class Application implements ClientCallback {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    private static final Map<Client, Application> clientApplicationMap = new HashMap<>();
    private RegistryServer registryServer = null;
    private final AuthenticationManager authenticationManager = AuthenticationManager.getInstance();
    String group;
    String name;
    int rank;
    Client client;
    RunLevel runLevel;
    String authenticationToken;

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

    public Client getClient() {
        return client;
    }

    public void setClient(Client newClient) {
        synchronized (clientApplicationMap) {
            clientApplicationMap.remove(this.client);
            newClient.setCallback(this);
            clientApplicationMap.put(newClient, this);
            this.client = newClient;
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
        logger.info("Updating run level of {} from {} to {}", getDisplayName(), runLevel, newRunLevel);
        this.runLevel = newRunLevel;
        syncWithRegistryMirrors();
    }

    public void handleRegistration(Client client) {
        setClient(client);
        setRunLevel(RunLevel.STARTING);
        AuthenticatedMessage registrationResponse = isRegistryInstance() ? createRegistryRegistrationResponse(this) : createRegistrationResponse(this);
        this.authenticationToken = authenticationManager.generateSecureToken();
        registrationResponse.setAuthenticationToken(this.authenticationToken);
        send(registrationResponse);
    }

    private boolean isRegistryInstance() {
        return this.name.equals(Constants.APPLICATION_NAME_REGISTRY)
                && this.group.equals(Constants.APPLICATION_GROUP_NAME);
    }

    public void send(Message message) {
        client.send(message);
    }

    @Override
    public void OnConnect(Client client) {

    }

    @Override
    public void OnDisconnect(Client client) {
        ApplicationManager.withNamespaceLock(group, name, () -> {
            logger.warn("{} disconnected from registry server.", this.getDisplayName());
            setRunLevel(RunLevel.KILLED);
            if (rank == RANK_PRIMARY) {
                ApplicationManager.getInstance().electLeader(group, name);
            }
        });
    }

    @Override
    public void OnMessage(Client client, Message message) {
        logger.info("Received message from {} type {}", client.getRemoteAddress(), message.getClass().getSimpleName());
        if (message instanceof ApplicationRankUpdate rankUpdate) {
            handleApplicationRankUpdate(rankUpdate);
        }
        if (message instanceof ApplicationSyncMessage applicationSyncMessage) {
            handleApplicationSyncMessage(applicationSyncMessage);
        }
    }

    private void handleApplicationSyncMessage(ApplicationSyncMessage syncMessage) {
        ApplicationManager applicationManager = ApplicationManager.getInstance();
        Application application;
        application = applicationManager.findApplication(syncMessage.getGroup(), syncMessage.getName(), syncMessage.getRank());
        if (application == null) {
            application = applicationManager.createApplication(syncMessage.getGroup(), syncMessage.getName(), syncMessage.getRank());
        }
        application.setAuthenticationToken(syncMessage.getAuthenticationToken());
        application.setRunLevel(syncMessage.getRunLevel());
        logger.info("Synced application {}. Run Level: {}", application.getDisplayName(), application.getRunLevel());
    }

    private void handleApplicationRankUpdate(ApplicationRankUpdate rankUpdate) {
        if (rankUpdate.getRank() != rank) {
            askToDie(String.format("Rank confirmation mismatch. Expected %d, got %d", getRank(), rankUpdate.getRank()));
            return;
        }
        setRunLevel(RunLevel.RUNNING);
    }

    private void askToDie(String reason) {
        logger.error("Asking {} to die. Reason: {}", getDisplayName(), reason);
        try {
            client.disconnect();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        setRunLevel(RunLevel.TERMINATED);
    }

    @Override
    public void OnError(Client client, Throwable throwable) {

    }

    public static Application getApplication(Client client) {
        synchronized (clientApplicationMap) {
            return clientApplicationMap.get(client);
        }
    }

    public boolean promote() {
        if (runLevel != RunLevel.RUNNING || client == null || !client.isConnected()) {
            logger.warn("Cannot promote {} — not running or disconnected (runLevel={}, connected={})",
                    getDisplayName(), runLevel, client != null && client.isConnected());
            return false;
        }
        logger.info("Promoting application {} to Primary", getDisplayName());
        setRank(RANK_PRIMARY);
        setRunLevel(RunLevel.CHANGING);
        try {
            send(new ApplicationRankUpdate(RANK_PRIMARY));
        } catch (Exception e) {
            logger.error("Failed to send promotion to {}. Reverting.", getDisplayName(), e);
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
            logger.warn(">>>>>>> Registry server is not initialized. Cannot sync {} with registry mirrors", getDisplayName());
            return;
        }
        if (RegistryServer.getRank() != RANK_PRIMARY) {
            return;
        }
        ApplicationSyncMessage message = new ApplicationSyncMessage(this);
        registryServer.getActiveMirrorApplications().forEach(app -> {
            logger.info("Syncing {} with {}", getDisplayName(), app.getDisplayName());
            app.send(message);
        });
    }
}
