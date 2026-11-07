package dev.heysulo.archon.sdk;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.sdk.exception.PrimaryRegistryCommunicationFailure;
import dev.heysulo.archon.sdk.registry.PrimaryRegistry;
import dev.heysulo.archon.sdk.registry.RegistryLookupService;
import dev.heysulo.archon.sdk.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

import static dev.heysulo.archon.sdk.constant.Constants.RANK_UNKNOWN;

public class ArchonInterface {
    private static final Logger logger = LoggerFactory.getLogger(ArchonInterface.class);
    private static final long PRIMARY_LOOKUP_RETRY_DELAY_MILLIS = 1_000;
    private static ArchonInterface INSTANCE;
    private PrimaryRegistry primaryRegistry;
    String primaryRegistryLocation;
    private boolean rankUpdateAvailable = false;
    int rank;
    String groupName;
    String applicationName;
    ArchonCallback callback;

    public static ArchonInterface getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ArchonInterface();
        }
        return INSTANCE;
    }

    public int initialize(String groupName, String applicationName, ArchonCallback callback) {
        logger.info("Initializing ArchonInterface");
        this.groupName  = groupName;
        this.applicationName = applicationName;
        this.callback = callback;
        setupPrimaryRegistryConnection();
        registerApplication();
        return rank;
    }

    private void setupPrimaryRegistryConnection() {
        RegistryLookupService registryLookupService = RegistryLookupService.getInstance();
        logger.info("Looking for primary registry");
        primaryRegistryLocation = waitForPrimaryRegistry(registryLookupService);
        if (primaryRegistryLocation == null) {
            throw new PrimaryRegistryCommunicationFailure("PRIMARY_REGISTRY_LOOKUP", new InterruptedException());
        }
        logger.info("Creating primary registry connection with {}", primaryRegistryLocation);
        primaryRegistry = PrimaryRegistry.getInstance(this);
        primaryRegistry.connect(primaryRegistryLocation);
    }

    public void reconnect() {
        logger.info("[Reconnect] Looking for primary registry");
        primaryRegistryLocation = waitForPrimaryRegistry(RegistryLookupService.getInstance());
        if (primaryRegistryLocation == null) {
            logger.warn("[Reconnect] Interrupted while looking for primary registry");
            return;
        }
        logger.info("[Reconnect] Found primary registry at {}", primaryRegistryLocation);
        primaryRegistry.connect(primaryRegistryLocation);
        ApplicationRegistrationMessage registrationMessage = new ApplicationRegistrationMessage(groupName, applicationName, "TBD");
        registrationMessage.setRank(rank);
        primaryRegistry.sendMessage(registrationMessage);
    }

    /**
     * Blocks until a primary registry is found, as applications and registries can start in any order.
     *
     * @return the primary's location, or {@code null} if interrupted
     */
    private String waitForPrimaryRegistry(RegistryLookupService registryLookupService) {
        String location = registryLookupService.search();
        while (location == null) {
            logger.info("No primary registry available. Retrying in {} ms", PRIMARY_LOOKUP_RETRY_DELAY_MILLIS);
            if (!waitBeforeRetrying()) {
                return null;
            }
            location = registryLookupService.search();
        }
        return location;
    }

    private boolean waitBeforeRetrying() {
        try {
            Thread.sleep(PRIMARY_LOOKUP_RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void registerApplication() {
        rankUpdateAvailable = false;
        ApplicationRegistrationMessage registrationMessage = new ApplicationRegistrationMessage(groupName, applicationName, "TBD");
        registrationMessage.setRank(RANK_UNKNOWN);
        logger.info("Registering application with Registry");
        primaryRegistry.sendMessage(registrationMessage);
        logger.info("Waiting for application rank update");
        try {
            Utils.waitForCondition(() -> rankUpdateAvailable, 5_000, 100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new PrimaryRegistryCommunicationFailure("REGISTRATION_RANK_UPDATE", e);
        }
    }

    public void onRankUpdateMessage(int newRank) {
        rankUpdateAvailable = true;
        logger.info("Received rank update. {} -> {}", rank, newRank);
        rank = newRank;
        callback.onRankUpdateMessage(newRank);
    }

    public void acknowledgeRank(int rank) {
        primaryRegistry.sendMessage(new ApplicationRankUpdate(rank));
    }

}