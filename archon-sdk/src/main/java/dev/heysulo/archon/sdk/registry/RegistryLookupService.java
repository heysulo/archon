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

import java.util.concurrent.TimeoutException;

public class RegistryLookupService implements ClientCallback {
    private static final long LOOKUP_TIMEOUT_MILLIS = 10_000;
    private static final long LOOKUP_POLL_MILLIS = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryLookupService.class);
    private static RegistryLookupService instance;
    private final EventLoopGroup eventLoopGroup =
            new NioEventLoopGroup(1, new DefaultThreadFactory("registry-lookup-worker", true));
    private volatile Boolean primaryRegistryFound = null;
    // The newest leadership term seen. A primary from an older term has been replaced and is about to terminate.
    private volatile long highestTerm = 0;

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
        for (String registryLocation : RegistryConstants.getRegistryLocations()) {
            if (isPrimaryRegistry(registryLocation)) {
                LOGGER.info("Primary registry found at {}", registryLocation);
                return registryLocation;
            }
        }
        return null;
    }

    private boolean isPrimaryRegistry(String registryLocation) {
        Client lookupConnection = new Client(registryLocation, RegistryConstants.PORT, this, eventLoopGroup);
        try {
            primaryRegistryFound = null;
            lookupConnection.connect();
            lookupConnection.send(new PrimaryRegistryLookupMessage());
            LOGGER.info("Waiting for registry lookup response from {}", registryLocation);
            Utils.waitForCondition(() -> primaryRegistryFound != null, LOOKUP_TIMEOUT_MILLIS, LOOKUP_POLL_MILLIS);
            if (!primaryRegistryFound) {
                LOGGER.info("{} is not the primary registry", registryLocation);
            }
            return primaryRegistryFound;
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
        return false;
    }

    /** Records a leadership term the application has seen, keeping the highest. */
    public synchronized void observeTerm(long term) {
        if (term > highestTerm) {
            highestTerm = term;
        }
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
        LOGGER.info("Connected to registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onDisconnect(Client client) {
        LOGGER.info("Disconnected from registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (message instanceof PrimaryRegistryLookupResponseMessage lookupResponseMessage) {
            boolean primary = lookupResponseMessage.getRank() == RegistryProtocol.RANK_PRIMARY;
            if (primary && lookupResponseMessage.getTerm() < highestTerm) {
                LOGGER.warn("Ignoring primary at {}: its term {} is older than term {}", client.getRemoteAddress(),
                        lookupResponseMessage.getTerm(), highestTerm);
                primary = false;
            }
            if (primary) {
                observeTerm(lookupResponseMessage.getTerm());
            }
            primaryRegistryFound = primary;
        }
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        LOGGER.error("Error on registry lookup client", throwable);
    }
}
