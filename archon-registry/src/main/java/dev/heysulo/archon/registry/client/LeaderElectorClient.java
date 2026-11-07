package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Candidate;
import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Decision;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LEADER_ELECTION_POLL_MILLIS;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LEADER_ELECTION_SCAN_DELAY;

public class LeaderElectorClient implements ClientCallback {
    private static final Logger logger = LoggerFactory.getLogger(LeaderElectorClient.class);
    private static LeaderElectorClient INSTANCE;
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("leader-elector-client-worker"));
    private final Map<Client, LeaderStatusMessage> peerStatus = new ConcurrentHashMap<>();
    private final List<Client> pendingPeers = new CopyOnWriteArrayList<>();
    private volatile Client primaryClient = null;
    private volatile boolean trueRankReceived = false;

    public static LeaderElectorClient getInstance() {
        if (INSTANCE == null) {
            synchronized (LeaderElectorClient.class) {
                INSTANCE = new LeaderElectorClient();
            }
        }
        return INSTANCE;
    }

    @Override
    public void onConnect(Client client) {
        logger.info("Connected to Registry at {}", client.getRemoteAddress());
        sendElectionMessage(client);
    }

    @Override
    public void onDisconnect(Client client) {
        logger.info("Leader Election Client disconnected from {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (message instanceof LeaderStatusMessage leaderStatusMessage) {
            handleLeaderStatusMessage(client, leaderStatusMessage);
        } else if (message instanceof RegistryRegistrationResponse registrationMessage) {
            handleRegistryRegistrationMessage(client, registrationMessage);
        } else {
            logger.error("Unsupported message type: {}", message.getClass().getName());
        }
    }

    private void handleRegistryRegistrationMessage(Client client, RegistryRegistrationResponse rankUpdateMessage) {
        logger.info("Received application registration message from {}. Rank: {}", client.getRemoteAddress(), rankUpdateMessage.getRank());
        RegistryServer.updateApplicationData(client, rankUpdateMessage);
        primaryClient = client;
        trueRankReceived = true;
    }

    private void handleLeaderStatusMessage(Client client, LeaderStatusMessage leaderStatusMessage) {
        logger.info("Received status from {}. Rank: {}", client.getRemoteAddress(), leaderStatusMessage.getRank());
        if (leaderStatusMessage.getRank() == RANK_PRIMARY) {
            primaryClient = client;
        }
        peerStatus.put(client, leaderStatusMessage);
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        logger.error("Registry client error", throwable);
    }

    /**
     * Runs one election, returning once this instance leads or has been confirmed by the primary.
     *
     * @param connectionAttempts how many times to try peers that do not accept a connection
     */
    public void start(int connectionAttempts) throws InterruptedException {
        reset();
        connectToAllRegistryInstances(connectionAttempts);
        while (true) {
            Decision decision = decide();
            if (decision == Decision.LEAD) {
                logElectionSummary(decision);
                RegistryServer.setRank(RANK_PRIMARY);
                return;
            }
            if (decision == Decision.FOLLOW_PRIMARY && trueRankReceived) {
                logElectionSummary(decision);
                RegistryServer.setPrimaryConnection(primaryClient);
                return;
            }
            requestPeerStatus();
            Thread.sleep(REGISTRY_LEADER_ELECTION_POLL_MILLIS);
        }
    }

    private Decision decide() {
        peerStatus.keySet().removeIf(client -> !client.isConnected());
        pendingPeers.removeIf(client -> !client.isConnected());
        if (!pendingPeers.isEmpty() && pendingPeers.stream().anyMatch(client -> !peerStatus.containsKey(client))) {
            return Decision.WAIT;
        }
        List<Candidate> peers = peerStatus.values().stream()
                .map(status -> new Candidate(status.getRank(), status.getStartTime(), status.getAddress()))
                .toList();
        return LeaderElectionPolicy.decide(
                new Candidate(RegistryServer.getRank(), Constants.APPLICATION_START_TIME, Constants.getMyAddress()),
                peers);
    }

    private void requestPeerStatus() {
        pendingPeers.forEach(this::sendElectionMessage);
    }

    private void sendElectionMessage(Client client) {
        try {
            client.send(new LeaderElectionMessage(
                    RegistryServer.getRank(), Constants.getMyAddress(), Constants.APPLICATION_START_TIME));
        } catch (RuntimeException e) {
            logger.info("Could not reach registry at {}: {}", client.getRemoteAddress(), e.getMessage());
        }
    }

    private void logElectionSummary(Decision decision) {
        logger.info("---- Leader Election Summary ----");
        peerStatus.forEach((client, status) ->
                logger.info("  {} => rank={}", client.getRemoteAddress(), status.getRank()));
        logger.info("  {} => rank={} [self]", Constants.getMyAddress(), RegistryServer.getRank());
        logger.info("  Decision: {}", decision);
        logger.info("---------------------------------");
    }

    private void reset() throws InterruptedException {
        trueRankReceived = false;
        primaryClient = null;
        for (Client client : pendingPeers) {
            client.disconnect();
        }
        pendingPeers.clear();
        peerStatus.clear();
    }

    private void connectToAllRegistryInstances(int connectionAttempts) throws InterruptedException {
        List<String> registryLocations = new ArrayList<>(Constants.getRegistryLocations());
        registryLocations.remove(Constants.getMyAddress());
        int attempts = 0;
        while (!registryLocations.isEmpty() && attempts++ < connectionAttempts) {
            List<String> successfullyConnected = new ArrayList<>();
            for (String address : registryLocations) {
                if (tryConnect(address)) {
                    successfullyConnected.add(address);
                }
            }
            registryLocations.removeAll(successfullyConnected);
            if (!registryLocations.isEmpty() && attempts < connectionAttempts) {
                Thread.sleep(REGISTRY_LEADER_ELECTION_SCAN_DELAY);
            }
        }

        if (!registryLocations.isEmpty()) {
            logger.warn("Registry client failed to connect after {} attempts. Remaining locations: {}", attempts, registryLocations);
        }
    }

    private boolean tryConnect(String address) {
        logger.info("Attempting to connect to {} for leader election", address);
        Client client = new Client(address, Constants.REGISTRY_PORT, this, workerGroup);
        Constants.TRUSTED_TIME_CLASSES.forEach(client.settings()::addTrustedPackage);
        client.settings().setHeartbeatInterval(Constants.REGISTRY_HEARTBEAT_INTERVAL);
        try {
            client.connect();
            pendingPeers.add(client);
            return true;
        } catch (Exception e) {
            logger.info("Connection to registry at {} failed. Error: {}", address, e.getMessage());
            return false;
        }
    }
}
