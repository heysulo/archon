package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import dev.heysulo.archon.sdk.constant.RegistryConstants;
import dev.heysulo.archon.sdk.utils.Utils;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeoutException;

public class RegistryLookupService implements ClientCallback {
    private static RegistryLookupService INSTANCE;
    private final Logger logger = LoggerFactory.getLogger(RegistryLookupService.class);
    private Boolean primaryRegistryFound = null;

    public static RegistryLookupService getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RegistryLookupService();
        }
        return INSTANCE;
    }

    public String search() {
        String primaryRegistryLocation = null;
        List<String> registryLocations = RegistryConstants.getRegistryLocations();
        for (String registryLocation : registryLocations) {
            Client tempRegistryConnection = new Client(registryLocation, RegistryConstants.PORT, this);
            try {
                primaryRegistryFound = null;
                tempRegistryConnection.connect();
                tempRegistryConnection.send(new PrimaryRegistryLookupMessage());
                logger.info("Waiting for registry lookup response from {}", registryLocation);
                Utils.waitForCondition(() -> primaryRegistryFound != null, 10_000, 100);
                if (primaryRegistryFound) {
                    logger.info("Primary registry found at {}", registryLocation);
                    primaryRegistryLocation = registryLocation;
                    break;
                } else {
                    logger.info("{} is not the primary registry", registryLocation);
                }
                tempRegistryConnection.disconnect();
            } catch (TimeoutException e) {
                logger.warn("Registry lookup response timed out: {}", registryLocation);
            } catch (Exception e) {
                logger.info("Connection to registry at {} failed. Error: {}", registryLocation, e.getMessage());
            }
        }
        return primaryRegistryLocation;
    }

    @Override
    public void OnConnect(Client client) {
        logger.info("Connected to registry at {}", client.getRemoteAddress());
    }

    @Override
    public void OnDisconnect(Client client) {
        logger.info("Disconnected from registry at {}", client.getRemoteAddress());
    }

    @Override
    public void OnMessage(Client client, Message message) {
        if (message instanceof PrimaryRegistryLookupResponseMessage lookupResponseMessage) {
            primaryRegistryFound = lookupResponseMessage.getRank() == 1 ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    @Override
    public void OnError(Client client, Throwable throwable) {
        logger.error("Error on registry lookup client", throwable);
    }
}
