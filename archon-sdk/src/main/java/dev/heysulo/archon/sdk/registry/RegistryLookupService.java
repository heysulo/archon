package dev.heysulo.archon.sdk.registry;

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
    private static RegistryLookupService INSTANCE;
    private final Logger logger = LoggerFactory.getLogger(RegistryLookupService.class);
    private final EventLoopGroup eventLoopGroup =
            new NioEventLoopGroup(1, new DefaultThreadFactory("registry-lookup-worker", true));
    private volatile Boolean primaryRegistryFound = null;

    public static RegistryLookupService getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RegistryLookupService();
        }
        return INSTANCE;
    }

    /**
     * Returns the location of the primary registry, or {@code null} if none could be found.
     */
    public String search() {
        for (String registryLocation : RegistryConstants.getRegistryLocations()) {
            if (isPrimaryRegistry(registryLocation)) {
                logger.info("Primary registry found at {}", registryLocation);
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
            logger.info("Waiting for registry lookup response from {}", registryLocation);
            Utils.waitForCondition(() -> primaryRegistryFound != null, LOOKUP_TIMEOUT_MILLIS, LOOKUP_POLL_MILLIS);
            if (!primaryRegistryFound) {
                logger.info("{} is not the primary registry", registryLocation);
            }
            return primaryRegistryFound;
        } catch (TimeoutException e) {
            logger.warn("Registry lookup response timed out: {}", registryLocation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Registry lookup interrupted: {}", registryLocation);
        } catch (Exception e) {
            logger.info("Connection to registry at {} failed. Error: {}", registryLocation, e.getMessage());
        } finally {
            close(lookupConnection);
        }
        return false;
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
        logger.info("Connected to registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onDisconnect(Client client) {
        logger.info("Disconnected from registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (message instanceof PrimaryRegistryLookupResponseMessage lookupResponseMessage) {
            primaryRegistryFound = lookupResponseMessage.getRank() == 1 ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        logger.error("Error on registry lookup client", throwable);
    }
}
