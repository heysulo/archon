package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Candidate;
import dev.heysulo.archon.registry.client.LeaderElectionPolicy.Decision;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderElectionMessage;
import dev.heysulo.archon.registry.messages.leaderelection.LeaderStatusMessage;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import dev.heysulo.archon.registry.messages.leaderelection.VoteRequest;
import dev.heysulo.archon.registry.messages.leaderelection.VoteResponse;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LEADER_ELECTION_POLL_MILLIS;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_LEADER_ELECTION_SCAN_DELAY;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_VOTE_LEASE;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_VOTE_RETRY_MAX_BACKOFF_MILLIS;
import static dev.heysulo.archon.registry.constants.Constants.REGISTRY_VOTE_TIMEOUT_MILLIS;

public class LeaderElectorClient implements ClientCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeaderElectorClient.class);
    private static final long VOTE_POLL_MILLIS = 20;
    private static final long NO_TERM_WON = -1;
    private static LeaderElectorClient instance;
    private final EventLoopGroup workerGroup =
            new NioEventLoopGroup(new DefaultThreadFactory("leader-elector-client-worker"));
    private final Map<String, Client> peers = new ConcurrentHashMap<>();
    // Connecting to an unreachable peer blocks until the TCP connect times out, so it never runs on the election
    // thread
    private final ExecutorService connector =
            Executors.newCachedThreadPool(new DefaultThreadFactory("leader-elector-connector", true));
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private final Map<Client, LeaderStatusMessage> peerStatus = new ConcurrentHashMap<>();
    private final Map<Client, VoteResponse> votes = new ConcurrentHashMap<>();
    private final Ballot ballot = new Ballot(REGISTRY_VOTE_LEASE, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    private final Object electionLock = new Object();
    private volatile Client primaryClient = null;
    private volatile boolean trueRankReceived = false;
    private volatile long ballotTerm = NO_TERM_WON;

    private LeaderElectorClient() {
    }

    public static synchronized LeaderElectorClient getInstance() {
        if (instance == null) {
            instance = new LeaderElectorClient();
        }
        return instance;
    }

    @Override
    public void onConnect(Client client) {
        LOGGER.info("Connected to Registry at {}", client.getRemoteAddress());
        sendElectionMessage(client);
    }

    @Override
    public void onDisconnect(Client client) {
        LOGGER.info("Leader Election Client disconnected from {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (message instanceof LeaderStatusMessage leaderStatusMessage) {
            handleLeaderStatusMessage(client, leaderStatusMessage);
        } else if (message instanceof RegistryRegistrationResponse registrationMessage) {
            handleRegistryRegistrationMessage(client, registrationMessage);
        } else if (message instanceof VoteResponse voteResponse) {
            handleVoteResponse(client, voteResponse);
        } else {
            LOGGER.error("Unsupported message type: {}", message.getClass().getName());
        }
    }

    private void handleRegistryRegistrationMessage(Client client, RegistryRegistrationResponse rankUpdateMessage) {
        LOGGER.info("Received application registration message from {}. Rank: {}, term: {}",
                client.getRemoteAddress(), rankUpdateMessage.getRank(), rankUpdateMessage.getTerm());
        synchronized (electionLock) {
            if (RegistryServer.getRank() == RANK_PRIMARY) {
                LOGGER.warn("Ignoring registration from {}: this instance already leads", client.getRemoteAddress());
                return;
            }
            RegistryServer.observeTerm(rankUpdateMessage.getTerm());
            RegistryServer.updateApplicationData(client, rankUpdateMessage);
            primaryClient = client;
            trueRankReceived = true;
        }
    }

    private void handleLeaderStatusMessage(Client client, LeaderStatusMessage leaderStatusMessage) {
        LOGGER.info("Received status from {}. Rank: {}, term: {}", client.getRemoteAddress(),
                leaderStatusMessage.getRank(), leaderStatusMessage.getTerm());
        RegistryServer.observeTerm(leaderStatusMessage.getTerm());
        if (leaderStatusMessage.getRank() == RANK_PRIMARY) {
            primaryClient = client;
        }
        peerStatus.put(client, leaderStatusMessage);
    }

    private void handleVoteResponse(Client client, VoteResponse voteResponse) {
        RegistryServer.observeTerm(voteResponse.getKnownTerm());
        if (voteResponse.getTerm() == ballotTerm) {
            votes.put(client, voteResponse);
        }
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        LOGGER.error("Registry client error", throwable);
    }

    /**
     * Decides whether to back another registry as primary, judging it against the registries this one can see.
     *
     * @param primaryKnown whether this registry is still connected to a primary
     */
    public boolean considerVote(VoteRequest request, boolean primaryKnown) {
        Candidate candidate = new Candidate(request.getRank(), request.getStartTime(), request.getAddress());
        List<Candidate> view = new ArrayList<>(visiblePeers());
        view.removeIf(peer -> peer.address().equals(candidate.address()));
        view.add(self());
        return ballot.consider(candidate, request.getTerm(), RegistryServer.getTerm(), primaryKnown, view);
    }

    /**
     * Runs one election, returning once this instance leads or has been confirmed by the primary.
     *
     * @param connectionAttempts how many times to try peers that do not accept a connection before deciding
     */
    public void start(int connectionAttempts) throws InterruptedException {
        reset();
        connectToAllRegistryInstances(connectionAttempts);
        while (true) {
            Outcome outcome = concludeElection();
            if (outcome == Outcome.CONCLUDED) {
                return;
            }
            if (outcome == Outcome.VOTE_LOST) {
                // Spreads out rival candidates so one of them can collect a majority
                Thread.sleep(ThreadLocalRandom.current().nextLong(REGISTRY_VOTE_RETRY_MAX_BACKOFF_MILLIS));
            }
            connectToMissingPeers();
            requestPeerStatus();
            Thread.sleep(REGISTRY_LEADER_ELECTION_POLL_MILLIS);
        }
    }

    private enum Outcome {
        CONCLUDED,
        UNDECIDED,
        VOTE_LOST
    }

    /**
     * Only a primary registers a peer, so a registration overrides the peer statuses, which may predate that peer
     * winning. The lock keeps a registration from arriving between winning and taking rank 1.
     */
    private Outcome concludeElection() throws InterruptedException {
        synchronized (electionLock) {
            if (trueRankReceived) {
                logElectionSummary(Decision.FOLLOW_PRIMARY);
                RegistryServer.setPrimaryConnection(primaryClient);
                return Outcome.CONCLUDED;
            }
            if (decide() != Decision.LEAD) {
                return Outcome.UNDECIDED;
            }
            long term = winVote();
            if (term == NO_TERM_WON) {
                return Outcome.VOTE_LOST;
            }
            RegistryServer.setTerm(term);
            RegistryServer.setRank(RANK_PRIMARY);
            logElectionSummary(Decision.LEAD);
            return Outcome.CONCLUDED;
        }
    }

    private Decision decide() {
        pruneDisconnectedPeers();
        if (peers.values().stream().anyMatch(client -> !peerStatus.containsKey(client))) {
            return Decision.WAIT;
        }
        return LeaderElectionPolicy.decide(self(), visiblePeers(), Constants.getQuorum());
    }

    /**
     * Asks every visible peer to back this instance for the next term.
     *
     * @return the term won, or {@link #NO_TERM_WON}
     */
    private long winVote() throws InterruptedException {
        long term = Math.max(RegistryServer.getTerm(), ballot.getVotedTerm()) + 1;
        if (!ballot.voteForSelf(Constants.getMyAddress(), term)) {
            return NO_TERM_WON;
        }
        votes.clear();
        ballotTerm = term;
        List<Client> voters = List.copyOf(peers.values());
        VoteRequest request = new VoteRequest(RegistryServer.getRank(), term, Constants.getMyAddress(),
                Constants.APPLICATION_START_TIME);
        voters.forEach(voter -> send(voter, request));

        int quorum = Constants.getQuorum();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGISTRY_VOTE_TIMEOUT_MILLIS);
        while (grantedVotes() + 1 < quorum && votes.size() < voters.size() && System.nanoTime() < deadline) {
            Thread.sleep(VOTE_POLL_MILLIS);
        }
        int granted = grantedVotes() + 1;
        ballotTerm = NO_TERM_WON;
        LOGGER.info("Vote for term {}: {} of {} registries in favour, {} needed", term, granted,
                Constants.getRegistryLocations().size(), quorum);
        return granted >= quorum ? term : NO_TERM_WON;
    }

    private int grantedVotes() {
        return (int) votes.values().stream().filter(VoteResponse::isGranted).count();
    }

    private Candidate self() {
        return new Candidate(RegistryServer.getRank(), Constants.APPLICATION_START_TIME, Constants.getMyAddress());
    }

    private List<Candidate> visiblePeers() {
        return peerStatus.entrySet().stream()
                .filter(entry -> entry.getKey().isConnected())
                .map(Map.Entry::getValue)
                .map(status -> new Candidate(status.getRank(), status.getStartTime(), status.getAddress()))
                .toList();
    }

    private void pruneDisconnectedPeers() {
        peerStatus.keySet().removeIf(client -> !client.isConnected());
        peers.values().removeIf(client -> !client.isConnected());
    }

    private void requestPeerStatus() {
        peers.values().forEach(this::sendElectionMessage);
    }

    private void sendElectionMessage(Client client) {
        send(client, new LeaderElectionMessage(RegistryServer.getRank(), RegistryServer.getTerm(),
                Constants.getMyAddress(), Constants.APPLICATION_START_TIME));
    }

    private void send(Client client, Message message) {
        try {
            client.send(message);
        } catch (RuntimeException e) {
            LOGGER.info("Could not reach registry at {}: {}", client.getRemoteAddress(), e.getMessage());
        }
    }

    private void logElectionSummary(Decision decision) {
        LOGGER.info("---- Leader Election Summary ----");
        peerStatus.forEach((client, status) ->
                LOGGER.info("  {} => rank={}", client.getRemoteAddress(), status.getRank()));
        LOGGER.info("  {} => rank={} [self]", Constants.getMyAddress(), RegistryServer.getRank());
        LOGGER.info("  Decision: {}, term: {}", decision, RegistryServer.getTerm());
        LOGGER.info("---------------------------------");
    }

    private void reset() throws InterruptedException {
        trueRankReceived = false;
        primaryClient = null;
        for (Client client : peers.values()) {
            client.disconnect();
        }
        peers.clear();
        peerStatus.clear();
        votes.clear();
    }

    // Gives every peer a chance to answer before deciding. Peers still missing afterwards keep being retried.
    private void connectToAllRegistryInstances(int connectionAttempts) throws InterruptedException {
        int attempts = 0;
        List<String> missing = missingPeerAddresses();
        while (!missing.isEmpty() && attempts++ < connectionAttempts) {
            connectToMissingPeers();
            Thread.sleep(REGISTRY_LEADER_ELECTION_SCAN_DELAY);
            missing = missingPeerAddresses();
        }
        if (!missing.isEmpty()) {
            LOGGER.warn("Registry client failed to connect after {} attempts. Remaining locations: {}", attempts,
                    missing);
        }
    }

    // Without a majority in view no one can lead, so peers that were down keep being retried
    private void connectToMissingPeers() {
        missingPeerAddresses().forEach(this::connectInBackground);
    }

    private void connectInBackground(String address) {
        if (!connecting.add(address)) {
            return;
        }
        connector.execute(() -> {
            try {
                tryConnect(address);
            } finally {
                connecting.remove(address);
            }
        });
    }

    private List<String> missingPeerAddresses() {
        pruneDisconnectedPeers();
        List<String> missing = new ArrayList<>(Constants.getRegistryLocations());
        missing.remove(Constants.getMyAddress());
        missing.removeAll(peers.keySet());
        return missing;
    }

    private void tryConnect(String address) {
        LOGGER.info("Attempting to connect to {} for leader election", address);
        Client client = new Client(address, Constants.REGISTRY_PORT, this, workerGroup);
        Constants.TRUSTED_TIME_CLASSES.forEach(client.settings()::addTrustedPackage);
        client.settings().setHeartbeatInterval(Constants.REGISTRY_HEARTBEAT_INTERVAL);
        try {
            client.connect();
            peers.put(address, client);
        } catch (Exception e) {
            LOGGER.info("Connection to registry at {} failed. Error: {}", address, e.getMessage());
        }
    }
}
