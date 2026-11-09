package dev.heysulo.archon.sdk;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRankUpdate;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;
import dev.heysulo.archon.sdk.config.ConfigReplicaClient;
import dev.heysulo.archon.sdk.config.ConfigRowSink;
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

/**
 * An application instance's connection to Archon: the one entry point of the SDK.
 *
 * <p>An instance {@linkplain #initialize registers} with the primary registry under its application definition
 * (group and name), and the registry gives it a rank. Rank 1 leads; the others are mirrors, ready to take over. The
 * SDK then keeps the instance connected, finding the new primary registry if the registries fail over, and tells the
 * application about rank changes, termination and configuration changes through its {@link ArchonCallback}.
 *
 * <p>Through this class an instance also:
 * <ul>
 *     <li>acknowledges its rank once ready for it, see {@link #acknowledgeRank}</li>
 *     <li>serves services and connects to others' by id, see {@link #createService} and {@link #connectToService}</li>
 *     <li>reads configurations, kept in memory and updated live, see {@link #getConfig}</li>
 * </ul>
 *
 * <p>A typical application:
 * <pre>{@code
 * public class Main implements ArchonCallback {
 *     private final ArchonInterface archon = ArchonInterface.getInstance();
 *
 *     void start() {
 *         int rank = archon.initialize("Shop", "Cart", this);
 *         becomeRole(rank);
 *     }
 *
 *     public void onRankUpdateMessage(int newRank) {
 *         becomeRole(newRank);
 *     }
 *
 *     private void becomeRole(int rank) {
 *         if (rank == 1) {
 *             archon.createService("cart", handler);   // serve as the leader
 *         } else {
 *             archon.stopService("cart");               // stand by as a mirror
 *         }
 *         archon.acknowledgeRank(rank);                // ready: the registry may now count on this instance
 *     }
 * }
 * }</pre>
 *
 * <p>Registries are found through {@code ARCHON_REGISTRY_LOCATIONS}. Other environment variables:
 * {@code ARCHON_BASE_PORT} (the port services are offset from), {@code ARCHON_ADVERTISED_HOST} (the address others
 * reach this instance's services at) and {@code ARCHON_NODE_NAME} (shown in the portal). For TLS,
 * {@code ARCHON_TLS}, {@code ARCHON_CA_FINGERPRINT} and {@code ARCHON_JOIN_TOKEN}; see
 * {@link dev.heysulo.archon.sdk.tls.ArchonTls}.
 *
 * <p>Thread safety: every method may be called from any thread, except that blocking calls must not run on the
 * registry connection's network thread. Callbacks arrive one at a time, in order, on a dedicated thread.
 */
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
    // The rank the application last acknowledged, reset whenever the rank changes
    private volatile int acknowledgedRank = RANK_UNKNOWN;
    private final Object rankLock = new Object();
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

    /**
     * The process's single instance. One process is one application instance, so there is only ever one.
     *
     * @return the instance, created on first use
     */
    public static synchronized ArchonInterface getInstance() {
        if (instance == null) {
            instance = new ArchonInterface();
        }
        return instance;
    }

    /**
     * Registers this process with the primary registry as an instance of an application definition, and blocks until
     * the registry assigns its rank.
     *
     * <p>It first waits, however long it takes, for a primary registry to answer: applications and registries may
     * start in any order. It then registers and waits up to 5 seconds for the rank. Unless this is one of Archon's own
     * components, it then connects to archon-config and waits up to 5 seconds for the configurations; if they do not
     * arrive in time, it returns without them and they are applied when they do.
     *
     * <p>The returned rank is also delivered to {@link ArchonCallback#onRankUpdateMessage}. Either way, the instance
     * stays starting until it {@linkplain #acknowledgeRank acknowledges} the rank. Call this once per process.
     *
     * @param groupName       the application definition's group, such as {@code Shop}
     * @param applicationName the application definition's name within the group, such as {@code Cart}
     * @param callback        hears about rank changes, termination and configuration changes
     * @return the rank the registry assigned: 1 for the leader, higher for mirrors
     * @throws PrimaryRegistryCommunicationFailure if interrupted while looking for a registry, or the registry does
     *                                             not assign a rank in time
     */
    public int initialize(String groupName, String applicationName, ArchonCallback callback) {
        LOGGER.info("Initializing ArchonInterface");
        this.groupName = groupName;
        this.applicationName = applicationName;
        this.events = new RegistryEvents(callback, callbackThread);
        this.services = new ServiceManager(groupName, applicationName, message -> primaryRegistry.sendMessage(message),
                this::isRegistryNetworkThread);
        // Created first, so a drop straight after connecting is not missed
        this.reconnector = new Reconnector(() -> RegistryLookupService.getInstance().search(),
                location -> primaryRegistry.connect(location), this::restoreRegistration,
                () -> primaryRegistry.isConnected(), reconnectThread, PRIMARY_LOOKUP_RETRY_DELAY_MILLIS);
        joinWithTls(groupName, applicationName);
        setupPrimaryRegistryConnection();
        registerApplication();
        startConfigs(groupName, applicationName);
        return rank;
    }

    // With TLS on, the instance needs its certificate before it can register as its application
    private void joinWithTls(String group, String name) {
        try {
            dev.heysulo.archon.sdk.tls.ArchonTls.get().join(group, name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrimaryRegistryCommunicationFailure("TLS_JOIN", e);
        }
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

    // archon-config and the portal hold every configuration through replicateConfigs instead
    private void startConfigs(String group, String name) {
        if ("Archon".equals(group) && ("Config".equals(name) || "Portal".equals(name))) {
            return;
        }
        configs = new ConfigClient(group, name, () -> rank,
                () -> primaryRegistry.getAuthenticationToken(), this::connectToConfigs, events::configChanged,
                reconnectThread);
        configs.start();
        if (!configs.awaitFirstSnapshot(FIRST_CONFIG_WAIT_MILLIS)) {
            LOGGER.info("Starting without configurations for now; they are applied when archon-config answers");
        }
    }

    /**
     * The configuration with this name as this instance sees it: its application's own if it has one, else its
     * group's, else the global one. Read from memory, so it never blocks; archon-config pushes every change, and
     * {@link ArchonCallback#onConfigUpdated} reports it.
     *
     * @param name the configuration's name, such as {@code ping.intervalMillis}
     * @return the value, or empty if no configuration with this name is visible to this instance, or configurations
     *         are unavailable (not initialized, or never reached archon-config)
     */
    public java.util.Optional<ConfigValue> getConfig(String name) {
        return configs == null ? java.util.Optional.empty() : configs.get(name);
    }

    /**
     * Every configuration this instance sees, the most specific per name, from memory.
     *
     * @return an unmodifiable snapshot keyed by name; empty if configurations are unavailable
     */
    public java.util.Map<String, ConfigValue> getConfigs() {
        return configs == null ? java.util.Map.of() : configs.all();
    }

    /**
     * A configuration's value as text, whatever its type.
     *
     * @param name     the configuration's name
     * @param fallback returned if no configuration with this name is visible
     * @return the value, or {@code fallback}
     */
    public String getConfigString(String name, String fallback) {
        return getConfig(name).map(ConfigValue::asString).orElse(fallback);
    }

    /**
     * A configuration's value as a whole number.
     *
     * @param name     the configuration's name
     * @param fallback returned if no configuration with this name is visible, or its value is not a whole number
     * @return the value, or {@code fallback}
     */
    public long getConfigLong(String name, long fallback) {
        try {
            return getConfig(name).map(ConfigValue::asLong).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * A configuration's value as an {@code int}.
     *
     * @param name     the configuration's name
     * @param fallback returned if no configuration with this name is visible, or its value is not a whole number
     *                 that fits an {@code int}
     * @return the value, or {@code fallback}
     */
    public int getConfigInt(String name, int fallback) {
        try {
            return getConfig(name).map(ConfigValue::asInt).orElse(fallback);
        } catch (NumberFormatException | ArithmeticException e) {
            return fallback;
        }
    }

    /**
     * A configuration's value as a number.
     *
     * @param name     the configuration's name
     * @param fallback returned if no configuration with this name is visible, or its value is not a number
     * @return the value, or {@code fallback}
     */
    public double getConfigDouble(String name, double fallback) {
        try {
            return getConfig(name).map(ConfigValue::asDouble).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * A configuration's value as a boolean: true only for {@code true}, ignoring case.
     *
     * @param name     the configuration's name
     * @param fallback returned if no configuration with this name is visible
     * @return the value, or {@code fallback}
     */
    public boolean getConfigBoolean(String name, boolean fallback) {
        return getConfig(name).map(ConfigValue::asBoolean).orElse(fallback);
    }

    /**
     * Changes the value this instance sees for a configuration: the most specific one with this name, which may be
     * shared with the rest of the group or every application. Blocks for up to 5 seconds until archon-config answers.
     * The change then reaches every instance that sees it, this one included, through
     * {@link ArchonCallback#onConfigUpdated}.
     *
     * @param name  the configuration's name
     * @param value the new value, in a form its type accepts: {@code 42} for an integer, {@code true} for a boolean
     * @return the configuration as stored
     * @throws ConfigurationException if no such configuration is visible, it is read only, the value does not fit its
     *                                type, or archon-config cannot be reached ({@link
     *                                ConfigurationException#isUnavailable()})
     */
    public ConfigValue setConfig(String name, String value) {
        return requireConfigs().set(name, value);
    }

    /**
     * Starts holding every stored configuration in {@code sink}, kept up to date by archon-config's primary. Only
     * Archon's own portal and archon-config's mirrors are allowed; archon-config refuses anyone else. Returns at
     * once; the rows arrive in the background.
     *
     * @param sink receives every stored configuration, then each change
     * @return the replication, which also manages configurations; close it to stop
     * @throws IllegalStateException if not initialized
     */
    public ConfigReplicaClient replicateConfigs(ConfigRowSink sink) {
        if (primaryRegistry == null) {
            throw new IllegalStateException("Initialize first");
        }
        ConfigReplicaClient replica = new ConfigReplicaClient(groupName, applicationName, () -> rank,
                () -> primaryRegistry.getAuthenticationToken(), this::connectToConfigs, sink, reconnectThread);
        replica.start();
        return replica;
    }

    private ServiceClient connectToConfigs(String serviceId, ServiceClientCallback callback) {
        return services.connect(serviceId, callback, ServiceClientOptions.defaults());
    }

    private ConfigClient requireConfigs() {
        if (configs == null) {
            throw ConfigurationException.unavailable("Configurations are not available", null);
        }
        return configs;
    }

    /**
     * Called by the SDK on a network thread when the registry connection drops; not for applications. Reconnecting
     * happens in the background.
     */
    public void onRegistryConnectionLost() {
        Reconnector current = reconnector;
        if (current != null && !terminated) {
            current.connectionLost();
        }
    }

    // Runs on the reconnect thread once connected to a primary registry again
    // The registry restores the instance as running only if it had acknowledged its rank
    private void restoreRegistration() {
        ApplicationRegistrationMessage message = createRegistrationMessage(rank);
        message.setReady(acknowledgedRank == rank);
        primaryRegistry.sendMessage(message);
        services.announceAll();
    }

    /**
     * Called by the SDK on a network thread when the registry terminates this instance; not for applications. Stops
     * talking to the registry for good, then tells the application through {@link ArchonCallback#onTerminated}.
     *
     * @param reason why the registry terminated this instance
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

    /**
     * Whether the registry terminated this instance. A terminated instance never reconnects; the process should end.
     *
     * @return true once terminated
     */
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

    /**
     * Called by the SDK on a network thread when the registry assigns a rank; not for applications. The application
     * hears about it through {@link ArchonCallback#onRankUpdateMessage}, on the callback thread.
     *
     * @param newRank the assigned rank
     */
    public void onRankUpdateMessage(int newRank) {
        LOGGER.info("Received rank update. {} -> {}", rank, newRank);
        synchronized (rankLock) {
            rank = newRank;
            acknowledgedRank = RANK_UNKNOWN;
        }
        rankUpdateAvailable = true;
        events.rankChanged(newRank);
    }

    /**
     * Tells the registry this instance is ready for its rank, moving it from starting (or changing, after a
     * promotion) to running. Call it from or after {@link ArchonCallback#onRankUpdateMessage}, once the role's work
     * is done: services started, connections made and warmed up. Until then the instance cannot be promoted.
     *
     * <p>A rank other than the current one is ignored: the rank changed again meanwhile, and the registry would treat
     * the stale acknowledgement as a mismatch and stop this instance. Acknowledging the same rank twice is harmless.
     * After a reconnect the SDK tells the registry itself whether the rank was acknowledged.
     *
     * @param acknowledged the rank the instance is now ready for, as given to
     *                     {@link ArchonCallback#onRankUpdateMessage}
     * @return whether the acknowledgement was sent: false if the rank is no longer current, the instance was
     *         terminated, or the registry is unreachable for now (it is then reported on reconnect)
     */
    public boolean acknowledgeRank(int acknowledged) {
        if (terminated) {
            return false;
        }
        // Held while checking and sending, so the rank cannot change in between
        synchronized (rankLock) {
            if (acknowledged != rank) {
                LOGGER.info("Not acknowledging rank {}: this instance's rank is now {}", acknowledged, rank);
                return false;
            }
            acknowledgedRank = acknowledged;
            try {
                primaryRegistry.sendMessage(new ApplicationRankUpdate(acknowledged));
                LOGGER.info("Acknowledged rank {}", acknowledged);
                return true;
            } catch (RuntimeException e) {
                // Disconnected. Re-registering after the reconnect reports the rank as acknowledged.
                LOGGER.warn("Could not acknowledge rank {} yet: {}", acknowledged, e.getMessage());
                return false;
            }
        }
    }

    /**
     * Whether this instance has acknowledged its current rank.
     *
     * @return false before the first rank, and after every rank change until it is acknowledged
     */
    public boolean isRankAcknowledged() {
        return rank != RANK_UNKNOWN && acknowledgedRank == rank;
    }

    /**
     * Looks up a service's definition and the instances serving it. Blocks until the registry answers. Most
     * applications need not call this: {@link #connectToService} looks the service up itself.
     *
     * @param serviceId the service's id, as defined in the portal; case sensitive
     * @return the definition, and the running instances in the order clients should use them
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service is unknown or the registry does not
     *                                                          answer
     * @throws IllegalStateException                            if not initialized, or called on the registry
     *                                                          connection's network thread
     */
    public ServiceInfo lookupService(String serviceId) {
        return requireServices().lookup(serviceId);
    }

    /**
     * Starts serving a service, on this instance's base port ({@code ARCHON_BASE_PORT}) plus the service's port
     * offset, and announces it to the registry so clients can find it. Blocks until the server listens and the
     * registry has accepted it. Only instances of the service's owning application definition may start it,
     * whatever their rank; for a primary/mirror service, usually only the leader does.
     *
     * <p>Clients reach it at {@code ARCHON_ADVERTISED_HOST}, or this machine's address if that is not set.
     *
     * @param serviceId the service's id, as defined in the portal
     * @param callback  handles the service's connections and messages
     * @return the started server
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service is unknown, owned by another
     *                                                          application, already running here, its port is in
     *                                                          use, or the registry refuses it
     * @throws IllegalStateException                            if not initialized, or called on the registry
     *                                                          connection's network thread
     */
    public Server createService(String serviceId, ServerCallback callback) {
        return createService(serviceId, callback, server -> { });
    }

    /**
     * Starts serving a service, as {@link #createService(String, ServerCallback)}, after adjusting the server.
     *
     * @param serviceId the service's id, as defined in the portal
     * @param callback  handles the service's connections and messages
     * @param configure adjusts the server before it starts listening, for example to trust the packages of the
     *                  service's messages or change its heartbeat
     * @return the started server
     * @throws dev.heysulo.archon.sdk.services.ServiceException as {@link #createService(String, ServerCallback)}
     * @throws IllegalStateException                            as {@link #createService(String, ServerCallback)}
     */
    public Server createService(String serviceId, ServerCallback callback, Consumer<Server> configure) {
        return requireServices().start(serviceId, callback, configure);
    }

    /**
     * Looks up a service and connects to it: to its serving instance for a primary/mirror service, or to a random
     * instance when load balanced. Blocks until connected. If the connection drops afterwards, as when the serving
     * instance fails over, the client looks the service up again and reconnects in the background, telling the
     * callback about each disconnect and connect.
     *
     * @param serviceId the service's id, as defined in the portal
     * @param callback  hears about connects, disconnects and the service's messages
     * @return the connected client; close it when done
     * @throws dev.heysulo.archon.sdk.services.ServiceException if the service is unknown, has no running instance, or
     *                                                          the first connection fails. A client that fails to
     *                                                          connect the first time does not retry; call again.
     * @throws IllegalStateException                            if not initialized, or called on the registry
     *                                                          connection's network thread
     */
    public ServiceClient connectToService(String serviceId, ServiceClientCallback callback) {
        return connectToService(serviceId, callback, settings -> { });
    }

    /**
     * Connects to a service, as {@link #connectToService(String, ServiceClientCallback)}, adjusting each connection
     * first.
     *
     * @param serviceId the service's id, as defined in the portal
     * @param callback  hears about connects, disconnects and the service's messages
     * @param configure adjusts each connection's settings before it connects, for example to trust the packages of
     *                  the service's messages
     * @return the connected client; close it when done
     * @throws dev.heysulo.archon.sdk.services.ServiceException as
     *                                                          {@link #connectToService(String, ServiceClientCallback)}
     * @throws IllegalStateException                            as
     *                                                          {@link #connectToService(String, ServiceClientCallback)}
     */
    public ServiceClient connectToService(String serviceId, ServiceClientCallback callback,
            Consumer<ProtocolSettings> configure) {
        return requireServices().connect(serviceId, callback, configure);
    }

    /**
     * Connects to a service with the given options, for example a warm-up for a service called rarely, whose first
     * calls would otherwise be slow. Blocks until connected and warmed up; every reconnect warms up again before the
     * callback hears about it.
     *
     * @param serviceId the service's id, as defined in the portal
     * @param callback  hears about connects, disconnects and the service's messages
     * @param options   the warm-up and connection settings, see {@link ServiceClientOptions}
     * @return the connected client; close it when done
     * @throws dev.heysulo.archon.sdk.services.ServiceException as
     *                                                          {@link #connectToService(String, ServiceClientCallback)}
     * @throws IllegalStateException                            as
     *                                                          {@link #connectToService(String, ServiceClientCallback)}
     */
    public ServiceClient connectToService(String serviceId, ServiceClientCallback callback,
            ServiceClientOptions options) {
        return requireServices().connect(serviceId, callback, options);
    }

    /**
     * Stops serving a service: tells the registry first, so lookups stop returning this instance, then closes the
     * server and its connections. Does nothing if the service is not running here, so a mirror may call it whenever
     * it takes its role.
     *
     * @param serviceId the service's id
     * @throws IllegalStateException if not initialized
     */
    public void stopService(String serviceId) {
        requireServices().stop(serviceId);
    }

    /**
     * Called by the SDK on a network thread with the registry's answers to service requests; not for applications.
     *
     * @param message the registry's answer
     */
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