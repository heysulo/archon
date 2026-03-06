package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_MIRROR;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LEADER_ELECTION_MAX_CONNECTION_ATTEMPTS;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LEADER_ELECTION_SCAN_DELAY;

public class LeaderElectorClient implements ClientCallback {
    private static final Logger logger = LoggerFactory.getLogger(LeaderElectorClient.class);
    private static LeaderElectorClient INSTANCE;
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("leader-elector-client-worker"));
    Map<Client, Integer> connectionStatus = new HashMap<>();
    private Client primaryClient = null;
    private boolean trueRankReceived = false;

    public static LeaderElectorClient getInstance() {
        if (INSTANCE == null) {
            synchronized (LeaderElectorClient.class) {
                INSTANCE = new LeaderElectorClient();
            }
        }
        return INSTANCE;
    }

    @Override
    public void OnConnect(Client client) {
        logger.info("Connected to Registry at {}", client.getRemoteAddress());
        LeaderElectionMessage message = new LeaderElectionMessage(Constants.getMyAddress(), Constants.APPLICATION_START_TIME);
        client.send(message);
    }

    @Override
    public void OnDisconnect(Client client) {
        logger.info("Leader Election Client disconnected from {}", client.getRemoteAddress());
    }

    @Override
    public void OnMessage(Client client, Message message) {
        if (message instanceof LeaderStatusMessage leaderStatusMessage) {
            handleLeaderStatusMessage(client, leaderStatusMessage);
        } else if (message instanceof RegistryRegistrationResponse registrationMessage) {
            handleRegistryRegistrationMessage(client, registrationMessage);
        } else {
            logger.error("Unsupported message type: {}", message.getClass().getName());
        }
    }

    private void handleRegistryRegistrationMessage(Client client, RegistryRegistrationResponse rankUpdateMessage) {
        logger.info("Received application registration message. Rank: {}", rankUpdateMessage.getRank());
        RegistryServer.updateApplicationData(client, rankUpdateMessage);
        primaryClient = client;
        trueRankReceived = true;
    }

    private void handleLeaderStatusMessage(Client client, LeaderStatusMessage leaderStatusMessage) {
        logger.info("Received LeaderElectionMessage from {}", client.getRemoteAddress());
        if (leaderStatusMessage.isForecastedPrimary() || leaderStatusMessage.isPrimary()) {
            RegistryServer.setRank(RANK_MIRROR);
        }
        if (leaderStatusMessage.isPrimary()) {
            primaryClient = client;
        }
        connectionStatus.put(client, leaderStatusMessage.getRank());
    }

    @Override
    public void OnError(Client client, Throwable throwable) {
        logger.error("Registry client error", throwable);
    }

    public void start() throws InterruptedException {
        reset();
        connectToAllRegistryInstances();
        waitForRegistryInstanceToRespond();
        finalizeRank();
        if (RegistryServer.getRank() != RANK_PRIMARY) {
            confirmPrimaryInstance();
        }
    }

    private void reset() {
        trueRankReceived = false;
        connectionStatus.clear();
        primaryClient = null;
    }

    private void confirmPrimaryInstance() throws InterruptedException {
        logger.info("Waiting for Primary's confirmation...");
        while (primaryClient == null || !trueRankReceived) {
            Thread.sleep(100);
        }
        RegistryServer.setPrimaryConnection(primaryClient);
    }

    private void connectToAllRegistryInstances() throws InterruptedException {
        List<String> registryLocations = new ArrayList<>(Constants.getRegistryLocations());
        registryLocations.remove(Constants.getMyAddress());
        int attempts = 0;
        while (!registryLocations.isEmpty() && attempts++ <= REGISTRY_LEADER_ELECTION_MAX_CONNECTION_ATTEMPTS) {
            List<String> successfullyConnected = new ArrayList<>();
            for (String address : registryLocations) {
                if (tryConnect(address)) {
                    successfullyConnected.add(address);
                }
            }
            registryLocations.removeAll(successfullyConnected);
            if (!registryLocations.isEmpty()) {
                Thread.sleep(REGISTRY_LEADER_ELECTION_SCAN_DELAY);
            }
        }

        if (!registryLocations.isEmpty()) {
            logger.warn("Registry client failed to connect after {} attempts. Remaining locations: {}", attempts, registryLocations);
        }
    }

    private void waitForRegistryInstanceToRespond() throws InterruptedException {
        while (connectionStatus.containsValue(RANK_UNKNOWN)) {
            LinkedList<String> pendingClients = new LinkedList<>();
            connectionStatus.forEach((client, rank) -> {
                if (rank == RANK_UNKNOWN) {
                    pendingClients.add(client.getRemoteAddress());
                }
            });
            logger.info("Waiting for response from {}", pendingClients);
            Thread.sleep(100);
        }
    }

    private boolean tryConnect(String address) {
        logger.info("Attempting to connect to {} for leader election", address);
        Client client = new Client(address, Constants.REGISTRY_PORT, this, workerGroup);
        try {
            client.connect();
            connectionStatus.put(client, RANK_UNKNOWN);
            return true;
        } catch (Exception e) {
            logger.info("Connection to registry at {} failed. Error: {}", address, e.getMessage());
            return false;
        }
    }

    private void finalizeRank() {
        logger.info("Finalizing rank decision. True rank received: {}", trueRankReceived ? "YES" : "NO");
        if (!trueRankReceived) {
            RegistryServer.setRank(
                    RegistryServer.getRank() == RANK_FORECASTED_PRIMARY
                            ? RANK_PRIMARY
                            : RANK_MIRROR
            );
        }
        logger.info("My Rank is {}", RegistryServer.getRank());
    }

}
