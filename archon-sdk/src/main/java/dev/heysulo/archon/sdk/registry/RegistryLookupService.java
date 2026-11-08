package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import dev.heysulo.archon.sdk.constant.RegistryConstants;
import dev.heysulo.archon.sdk.utils.Utils;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.ToLongFunction;

public class RegistryLookupService implements ClientCallback {
    static final long NOT_PRIMARY = -1;
    private static final long LOOKUP_TIMEOUT_MILLIS = 5_000;
    private static final long LOOKUP_POLL_MILLIS = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryLookupService.class);
    private static RegistryLookupService instance;
    private final EventLoopGroup eventLoopGroup =
            new NioEventLoopGroup(1, new DefaultThreadFactory("registry-lookup-worker", true));
    // The answer to the lookup in progress: the responder's term if it is primary, NOT_PRIMARY otherwise
    private volatile Long answer = null;

    private RegistryLookupService() {
    }

    public static synchronized RegistryLookupService getInstance() {
        if (instance == null) {
            instance = new RegistryLookupService();
        }
        return instance;
    }

    /**
     * Returns the location of the primary registry, or {@code null} if none could be found.
     */
    public String search() {
        String location = choosePrimary(RegistryConstants.getRegistryLocations(), this::primaryTerm);
        if (location != null) {
            LOGGER.info("Primary registry found at {}", location);
        }
        return location;
    }

    /**
     * Asks every registry and picks the primary. Two can claim to be primary for a short while, when a partitioned
     * primary has not yet been voted out, so the newest term wins. A single primary is always accepted, whatever its
     * term: terms live only in the registries' memory, so a cluster restarted from scratch counts from 1 again, and
     * insisting on a term at least as new as one seen before would ignore it forever.
     *
     * @param primaryTerm a registry's term if it is primary, or {@link #NOT_PRIMARY}
     */
    static String choosePrimary(List<String> locations, ToLongFunction<String> primaryTerm) {
        String best = null;
        long bestTerm = NOT_PRIMARY;
        for (String location : locations) {
            long term = primaryTerm.applyAsLong(location);
            if (term == NOT_PRIMARY) {
                continue;
            }
            if (best != null) {
                LOGGER.warn("Both {} (term {}) and {} (term {}) claim to be primary. Following the newer term", best,
                        bestTerm, location, term);
            }
            if (best == null || term > bestTerm) {
                best = location;
                bestTerm = term;
            }
        }
        return best;
    }

    private long primaryTerm(String registryLocation) {
        Client lookupConnection = new Client(registryLocation, RegistryConstants.PORT, this, eventLoopGroup);
        lookupConnection.settings().setConnectTimeout(RegistryConstants.CONNECT_TIMEOUT);
        try {
            answer = null;
            lookupConnection.connect();
            lookupConnection.send(new PrimaryRegistryLookupMessage());
            LOGGER.debug("Waiting for registry lookup response from {}", registryLocation);
            Utils.waitForCondition(() -> answer != null, LOOKUP_TIMEOUT_MILLIS, LOOKUP_POLL_MILLIS);
            if (answer == NOT_PRIMARY) {
                LOGGER.debug("{} is not the primary registry", registryLocation);
            }
            return answer;
        } catch (TimeoutException e) {
            LOGGER.warn("Registry lookup response timed out: {}", registryLocation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Registry lookup interrupted: {}", registryLocation);
        } catch (Exception e) {
            LOGGER.info("Connection to registry at {} failed. Error: {}", registryLocation, e.getMessage());
        } finally {
            close(lookupConnection);
        }
        return NOT_PRIMARY;
    }

    /**
     * Records a term reported by the registry. Lookups compare the terms registries answer with directly, so this
     * is informational.
     */
    public void observeTerm(long term) {
        LOGGER.debug("Registry reported term {}", term);
    }

    private void close(Client lookupConnection) {
        try {
            lookupConnection.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onConnect(Client client) {
        LOGGER.debug("Connected to registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onDisconnect(Client client) {
        LOGGER.debug("Disconnected from registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (message instanceof PrimaryRegistryLookupResponseMessage lookup) {
            answer = lookup.getRank() == RegistryProtocol.RANK_PRIMARY ? lookup.getTerm() : NOT_PRIMARY;
        }
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        LOGGER.error("Error on registry lookup client", throwable);
    }
}
