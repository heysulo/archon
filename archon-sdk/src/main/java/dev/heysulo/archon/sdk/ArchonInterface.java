package dev.heysulo.archon.sdk;

import dev.heysulo.archon.dictionary.sdk.ApplicationRegistrationMessage;
import dev.heysulo.archon.sdk.exception.PrimaryRegistryCommunicationFailure;
import dev.heysulo.archon.sdk.registry.PrimaryRegistry;
import dev.heysulo.archon.sdk.registry.RegistryLookupService;
import dev.heysulo.archon.sdk.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

public class ArchonInterface {
    private static final Logger logger = LoggerFactory.getLogger(ArchonInterface.class);
    private static ArchonInterface INSTANCE;
    private PrimaryRegistry primaryRegistry;
    String primaryRegistryLocation;
    private boolean rankUpdateAvailable = false;
    int rank;
    String groupName;
    String applicationName;

    public static ArchonInterface getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ArchonInterface();
        }
        return INSTANCE;
    }

    public int initialize(String groupName, String applicationName) {
        this.groupName  = groupName;
        this.applicationName = applicationName;
        setupPrimaryRegistryConnection();
        registerApplication();
        return rank;
    }

    private void setupPrimaryRegistryConnection() {
        logger.info("Initializing ArchonInterface");
        RegistryLookupService registryLookupService = RegistryLookupService.getInstance();
        logger.info("Looking for primary registry");
        primaryRegistryLocation = registryLookupService.search();
        logger.info("Creating primary registry connection with {}", primaryRegistryLocation);
        primaryRegistry = PrimaryRegistry.getInstance(this);
        primaryRegistry.connect(primaryRegistryLocation);
    }

    private void registerApplication() {
        rankUpdateAvailable = false;
        ApplicationRegistrationMessage registrationMessage = new ApplicationRegistrationMessage(groupName, applicationName, "TBD");
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
    }

}