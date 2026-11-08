package dev.heysulo.archon.sdk;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;
import dev.heysulo.archon.sdk.config.ConfigAdmin;
import dev.heysulo.archon.sdk.config.ConfigClient;
import dev.heysulo.archon.sdk.config.ConfigValue;
import dev.heysulo.archon.sdk.config.ConfigurationException;
import dev.heysulo.archon.sdk.exception.PrimaryRegistryCommunicationFailure;
import dev.heysulo.archon.sdk.registry.PrimaryRegistry;
import dev.heysulo.archon.sdk.registry.RegistryLookupService;
import dev.heysulo.archon.sdk.services.ServiceClient;
import dev.heysulo.archon.sdk.services.ServiceClientCallback;
import dev.heysulo.archon.sdk.services.ServiceClientOptions;
import dev.heysulo.archon.sdk.services.ServiceInfo;
import dev.heysulo.archon.sdk.services.ServiceManager;
import dev.heysulo.archon.sdk.utils.Utils;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.common.ProtocolSettings;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private volatile ServiceManager services;
    private volatile RegistryEvents events;
    private volatile Reconnector reconnector;
    private volatile boolean terminated;
    private volatile ConfigClient configs;
    // How long initialize waits for the first configurations when archon-config is running
    private static final long FIRST_CONFIG_WAIT_MILLIS = 5_000;
    // Rank and termination callbacks, one at a time and in order, never on a network thread
    private final ScheduledExecutorService callbackThread =
            Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("archon-callbacks", true));
    // Finding and reconnecting to the primary registry, which blocks
    private final ScheduledExecutorService reconnectThread =
            Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("archon-reconnect", true));

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
        this.events = new RegistryEvents(callback, this::confirmRank, callbackThread);
        this.services = new ServiceManager(groupName, applicationName, message -> primaryRegistry.sendMessage(message),
                this::isRegistryNetworkThread);
        // Created first, so a drop straight after connecting is not missed
        this.reconnector = new Reconnector(() -> RegistryLookupService.getInstance().search(),
                location -> primaryRegistry.connect(location), this::restoreRegistration,
                () -> primaryRegistry.isConnected(), reconnectThread, PRIMARY_LOOKUP_RETRY_DELAY_MILLIS);
        setupPrimaryRegistryConnection();
        registerApplication();
        startConfigs(groupName, applicationName);
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

    // archon-config itself does not read configurations through itself
    private void startConfigs(String group, String name) {
        if ("Archon".equals(group) && "Config".equals(name)) {
            return;
        }
        configs = new ConfigClient(group, name, () -> rank,
                () -> primaryRegistry.getAuthenticationToken(),
                (serviceId, serviceCallback) -> services.connect(serviceId, serviceCallback,
                        dev.heysulo.archon.sdk.services.ServiceClientOptions.defaults()),
                events::configChanged, reconnectThread);
        configs.start();
        if (!configs.awaitFirstSnapshot(FIRST_CONFIG_WAIT_MILLIS)) {
            LOGGER.info("Starting without configurations for now; they are applied when archon-config answers");
        }
    }

    /** The configuration with this name as this instance sees it, from the in-memory cache. */
    public java.util.Optional<ConfigValue> getConfig(String name) {
        return configs == null ? java.util.Optional.empty() : configs.get(name);
    }

    public java.util.Map<String, ConfigValue> getConfigs() {
        return configs == null ? java.util.Map.of() : configs.all();
    }

    public String getConfigString(String name, String fallback) {
        return getConfig(name).map(ConfigValue::asString).orElse(fallback);
    }

    public long getConfigLong(String name, long fallback) {
        try {
            return getConfig(name).map(ConfigValue::asLong).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int getConfigInt(String name, int fallback) {
        try {
            return getConfig(name).map(ConfigValue::asInt).orElse(fallback);
        } catch (NumberFormatException | ArithmeticException e) {
            return fallback;
        }
    }

    public double getConfigDouble(String name, double fallback) {
        try {
            return getConfig(name).map(ConfigValue::asDouble).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getConfigBoolean(String name, boolean fallback) {
        return getConfig(name).map(ConfigValue::asBoolean).orElse(fallback);
    }

    /**
     * Changes the value this instance sees for a configuration. Blocks until archon-config answers.
     *
     * @throws ConfigurationException if unknown, read only, of the wrong type, or
     *                                                              archon-config is unreachable
     */
    public ConfigValue setConfig(String name, String value) {
        return requireConfigs().set(name, value);
    }

    /** Manages every configuration. archon-config allows this only to Archon / Portal. */
    public ConfigAdmin configAdmin() {
        return requireConfigs();
    }

    private ConfigClient requireConfigs() {
        if (configs == null) {
            throw ConfigurationException.unavailable("Configurations are not available", null);
        }
        return configs;
    }

    /** Called on a network thread when the registry connection drops. Reconnecting happens elsewhere. */
    public void onRegistryConnectionLost() {
        Reconnector current = reconnector;
        if (current != null && !terminated) {
            current.connectionLost();
        }
    }

    // Runs on the reconnect thread once connected to a primary registry again
    private void restoreRegistration() {
        primaryRegistry.sendMessage(createRegistrationMessage(rank));
        services.announceAll();
    }

    /**
     * Called on a network thread when the registry terminates this instance. Stops talking to the registry for good,
     * then tells the application.
     */
    public void onTerminated(String reason) {
        terminated = true;
        Reconnector current = reconnector;
        if (current != null) {
            current.stop();
        }
        primaryRegistry.disconnect();
        events.terminated(reason);
    }

    public boolean isTerminated() {
        return terminated;
    }

    private boolean isRegistryNetworkThread() {
        return primaryRegistry != null && primaryRegistry.isNetworkThread();
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
        InstanceDetails details = InstanceDetails.fromEnvironment();
        String address = details.address() != null ? details.address() : IP_ADDRESS_PLACEHOLDER;
        ApplicationRegistrationMessage message =
                new ApplicationRegistrationMessage(applicationName, groupName, address);
        message.setRank(requestedRank);
        message.setDetails(details);
        return message;
    }

    /** Called on a network thread. The application hears about it on the callback thread. */
    public void onRankUpdateMessage(int newRank) {
        LOGGER.info("Received rank update. {} -> {}", rank, newRank);
        rank = newRank;
        rankUpdateAvailable = true;
        events.rankChanged(newRank);
    }

    /**
     * Confirms a rank to the registry.
     *
     * @deprecated The SDK confirms every rank itself once {@link ArchonCallback#onRankUpdateMessage} returns.
     *             Calling this as well is harmless.
     */
    @Deprecated
    public void acknowledgeRank(int rank) {
        confirmRank(rank);
    }

    private void confirmRank(int confirmedRank) {
        if (terminated) {
            return;
        }
        try {
            primaryRegistry.sendMessage(new ApplicationRankUpdate(confirmedRank));
        } catch (RuntimeException e) {
            // Disconnected. Re-registering after the reconnect confirms the rank again.
            LOGGER.warn("Could not confirm rank {}: {}", confirmedRank, e.getMessage());
        }
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

    /**
     * Connects to a service with the given options, for example a warm-up for services called rarely. Blocks until
     * connected and warmed up.
     *
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service is unknown, has no instance to connect
     *                                                          to, or the connection fails
     */
    public ServiceClient connectToService(String serviceId, ServiceClientCallback callback,
            ServiceClientOptions options) {
        return requireServices().connect(serviceId, callback, options);
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