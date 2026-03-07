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
        primaryRegistryLocation = registryLookupService.search();
        logger.info("Creating primary registry connection with {}", primaryRegistryLocation);
        primaryRegistry = PrimaryRegistry.getInstance(this);
        primaryRegistry.connect(primaryRegistryLocation);
    }

    public void reconnect() {
        logger.info("[Reconnect] Looking for primary registry");
        primaryRegistryLocation = null;
        RegistryLookupService registryLookupService = RegistryLookupService.getInstance();
        while (primaryRegistryLocation == null) {
            primaryRegistryLocation = registryLookupService.search();
        }
        logger.info("[Reconnect] Found primary registry at {}", primaryRegistryLocation);
        primaryRegistry.connect(primaryRegistryLocation);
        ApplicationRegistrationMessage registrationMessage = new ApplicationRegistrationMessage(groupName, applicationName, "TBD");
        registrationMessage.setRank(rank);
        primaryRegistry.sendMessage(registrationMessage);
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