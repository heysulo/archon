package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import dev.heysulo.archon.sdk.constant.RegistryConstants;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.ToLongFunction;

/**
 * Finds the primary registry. Safe to call from several threads at once: each lookup has its own connection and
 * waits for its own answer.
 */
public class RegistryLookupService {
    static final long NOT_PRIMARY = -1;
    private static final long LOOKUP_TIMEOUT_MILLIS = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryLookupService.class);
    private static RegistryLookupService instance;
    private final EventLoopGroup eventLoopGroup =
            new NioEventLoopGroup(1, new DefaultThreadFactory("registry-lookup-worker", true));

    private RegistryLookupService() {
    }

    /** @return the process's single lookup service */
    public static synchronized RegistryLookupService getInstance() {
        if (instance == null) {
            instance = new RegistryLookupService();
        }
        return instance;
    }

    /**
     * Asks every registry in {@code ARCHON_REGISTRY_LOCATIONS} which is primary, each with its own connection, and
     * picks it. If two claim to be primary for a moment, as when a cut-off primary has not yet been voted out, the
     * one with the newer term wins. Blocks for up to 2 seconds per registry to connect and 5 more to answer.
     *
     * @return the primary registry's address, or {@code null} if none answered as primary
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
        Lookup lookup = new Lookup();
        Client lookupConnection = new Client(registryLocation, RegistryConstants.PORT, lookup, eventLoopGroup);
        lookupConnection.settings().setConnectTimeout(RegistryConstants.CONNECT_TIMEOUT);
        try {
            lookupConnection.connect();
            lookupConnection.send(new PrimaryRegistryLookupMessage());
            LOGGER.debug("Waiting for registry lookup response from {}", registryLocation);
            long term = lookup.answer.get(LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (term == NOT_PRIMARY) {
                LOGGER.debug("{} is not the primary registry", registryLocation);
            }
            return term;
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
     *
     * @param term the primary registry's term, which grows with every election
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

    /**
     * One lookup's connection callback. Each lookup has its own, so two threads looking up at once cannot take each
     * other's answers, as they could when one shared field held the answer to whichever lookup was in progress.
     */
    static final class Lookup implements ClientCallback {
        final CompletableFuture<Long> answer = new CompletableFuture<>();

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
            if (message instanceof PrimaryRegistryLookupResponseMessage response) {
                answer.complete(response.getRank() == RegistryProtocol.RANK_PRIMARY ? response.getTerm() : NOT_PRIMARY);
            }
        }

        @Override
        public void onError(Client client, Throwable throwable) {
            LOGGER.error("Error on registry lookup client", throwable);
        }
    }
}
