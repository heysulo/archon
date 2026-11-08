package dev.heysulo.archon.sdk;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.sdk.exception.PrimaryRegistryCommunicationFailure;
import dev.heysulo.archon.sdk.registry.PrimaryRegistry;
import dev.heysulo.archon.sdk.registry.RegistryLookupService;
import dev.heysulo.archon.sdk.services.ServiceClient;
import dev.heysulo.archon.sdk.services.ServiceClientCallback;
import dev.heysulo.archon.sdk.services.ServiceInfo;
import dev.heysulo.archon.sdk.services.ServiceManager;
import dev.heysulo.archon.sdk.utils.Utils;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.common.ProtocolSettings;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static dev.heysulo.archon.dictionary.sdk.RegistryProtocol.RANK_UNKNOWN;

public class ArchonInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchonInterface.class);
    private static final long PRIMARY_LOOKUP_RETRY_DELAY_MILLIS = 1_000;
    private static final long REGISTRATION_TIMEOUT_MILLIS = 5_000;
    private static final long REGISTRATION_POLL_MILLIS = 100;
    private static final String IP_ADDRESS_PLACEHOLDER = "TBD";
    private static ArchonInterface instance;
    private PrimaryRegistry primaryRegistry;
    private String primaryRegistryLocation;
    private volatile boolean rankUpdateAvailable = false;
    private volatile int rank;
    private String groupName;
    private String applicationName;
    private ArchonCallback callback;
    private volatile ServiceManager services;

    private ArchonInterface() {
    }

    public static synchronized ArchonInterface getInstance() {
        if (instance == null) {
            instance = new ArchonInterface();
        }
        return instance;
    }

    public int initialize(String groupName, String applicationName, ArchonCallback callback) {
        LOGGER.info("Initializing ArchonInterface");
        this.groupName = groupName;
        this.applicationName = applicationName;
        this.callback = callback;
        this.services = new ServiceManager(groupName, applicationName, message -> primaryRegistry.sendMessage(message));
        setupPrimaryRegistryConnection();
        registerApplication();
        return rank;
    }

    private void setupPrimaryRegistryConnection() {
        RegistryLookupService registryLookupService = RegistryLookupService.getInstance();
        LOGGER.info("Looking for primary registry");
        primaryRegistryLocation = waitForPrimaryRegistry(registryLookupService);
        if (primaryRegistryLocation == null) {
            throw new PrimaryRegistryCommunicationFailure("PRIMARY_REGISTRY_LOOKUP", new InterruptedException());
        }
        LOGGER.info("Creating primary registry connection with {}", primaryRegistryLocation);
        primaryRegistry = PrimaryRegistry.getInstance(this);
        primaryRegistry.connect(primaryRegistryLocation);
    }

    public void reconnect() {
        LOGGER.info("[Reconnect] Looking for primary registry");
        primaryRegistryLocation = waitForPrimaryRegistry(RegistryLookupService.getInstance());
        if (primaryRegistryLocation == null) {
            LOGGER.warn("[Reconnect] Interrupted while looking for primary registry");
            return;
        }
        LOGGER.info("[Reconnect] Found primary registry at {}", primaryRegistryLocation);
        primaryRegistry.connect(primaryRegistryLocation);
        primaryRegistry.sendMessage(createRegistrationMessage(rank));
        services.announceAll();
    }

    /**
     * Blocks until a primary registry is found, as applications and registries can start in any order.
     *
     * @return the primary's location, or {@code null} if interrupted
     */
    private String waitForPrimaryRegistry(RegistryLookupService registryLookupService) {
        String location = registryLookupService.search();
        while (location == null) {
            LOGGER.info("No primary registry available. Retrying in {} ms", PRIMARY_LOOKUP_RETRY_DELAY_MILLIS);
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
        LOGGER.info("Registering application with Registry");
        primaryRegistry.sendMessage(createRegistrationMessage(RANK_UNKNOWN));
        LOGGER.info("Waiting for application rank update");
        try {
            Utils.waitForCondition(() -> rankUpdateAvailable, REGISTRATION_TIMEOUT_MILLIS, REGISTRATION_POLL_MILLIS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new PrimaryRegistryCommunicationFailure("REGISTRATION_RANK_UPDATE", e);
        }
    }

    private ApplicationRegistrationMessage createRegistrationMessage(int requestedRank) {
        return registrationMessage(groupName, applicationName, requestedRank);
    }

    // The message takes the application name first
    static ApplicationRegistrationMessage registrationMessage(String groupName, String applicationName,
            int requestedRank) {
        ApplicationRegistrationMessage message =
                new ApplicationRegistrationMessage(applicationName, groupName, IP_ADDRESS_PLACEHOLDER);
        message.setRank(requestedRank);
        return message;
    }

    public void onRankUpdateMessage(int newRank) {
        rankUpdateAvailable = true;
        LOGGER.info("Received rank update. {} -> {}", rank, newRank);
        rank = newRank;
        callback.onRankUpdateMessage(newRank);
    }

    public void acknowledgeRank(int rank) {
        primaryRegistry.sendMessage(new ApplicationRankUpdate(rank));
    }

    /**
     * Looks up a service's definition and where it is running. Blocks until the registry answers.
     *
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service is unknown or the registry does not
     *                                                          answer
     */
    public ServiceInfo lookupService(String serviceId) {
        return requireServices().lookup(serviceId);
    }

    /**
     * Starts serving a service, on this instance's base port ({@code ARCHON_BASE_PORT}) plus the service's offset.
     * Only instances of the service's application definition may start it, whatever their rank.
     *
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service cannot be started
     */
    public Server createService(String serviceId, ServerCallback callback) {
        return createService(serviceId, callback, server -> { });
    }

    /**
     * @param configure adjusts the server, for example its trusted packages, before it starts listening
     */
    public Server createService(String serviceId, ServerCallback callback, Consumer<Server> configure) {
        return requireServices().start(serviceId, callback, configure);
    }

    /**
     * Looks up a service and connects to it: to its primary, or to a random instance when load balanced. If the
     * connection drops, the client looks the service up again and reconnects. Blocks until connected.
     *
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service is unknown, has no instance to connect
     *                                                          to, or the connection fails
     */
    public ServiceClient connectToService(String serviceId, ServiceClientCallback callback) {
        return connectToService(serviceId, callback, settings -> { });
    }

    /**
     * @param configure adjusts each connection's settings, for example trusted packages, before it connects
     */
    public ServiceClient connectToService(String serviceId, ServiceClientCallback callback,
            Consumer<ProtocolSettings> configure) {
        return requireServices().connect(serviceId, callback, configure);
    }

    public void stopService(String serviceId) {
        requireServices().stop(serviceId);
    }

    public void onServiceResponse(Message message) {
        if (services != null) {
            services.onResponse(message);
        }
    }

    private ServiceManager requireServices() {
        if (services == null) {
            throw new IllegalStateException("ArchonInterface is not initialized");
        }
        return services;
    }

}