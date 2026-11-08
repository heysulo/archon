package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;
import dev.heysulo.archon.sdk.ArchonCallback;
import dev.heysulo.archon.sdk.ArchonInterface;
import dev.heysulo.archon.sdk.config.ConfigReplicaClient;
import dev.heysulo.archon.sdk.services.ServiceException;
import dev.heysulo.databridge.core.server.Server;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * archon-config: stores configurations in the archon schema and serves them to applications through the SDK.
 *
 * <p>Run any number of replicas. Each registers as {@code Archon / Config}; the primary serves the archon-config
 * service, on {@code ARCHON_BASE_PORT} plus the service's offset, and mirrors take over when it goes. Configured with
 * {@code ARCHON_REGISTRY_LOCATIONS}, {@code ARCHON_BASE_PORT}, {@code ARCHON_ADVERTISED_HOST}, and the archon schema's
 * {@code ARCHON_DB_URL}, {@code ARCHON_DB_USER} and {@code ARCHON_DB_PASSWORD}.
 */
public final class ConfigApplication implements ArchonCallback {
    static final String GROUP = "Archon";
    static final String NAME = "Config";
    static final String SERVICE = "archon-config";
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigApplication.class);
    // Picks up changes other replicas or people made directly in the database
    // A safety net only: the primary reads back each change as it makes it, and mirrors are pushed every change.
    // This catches writes from anywhere else, such as a primary that was cut off but kept writing.
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final long RETRY_MILLIS = 2_000;

    private final ArchonInterface archon = ArchonInterface.getInstance();
    private final ConfigService service;
    private final ConfigServer handler;
    // Rank changes start and stop the service one at a time
    private final ScheduledExecutorService roles = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "config-role");
        thread.setDaemon(false);
        return thread;
    });
    private volatile int rank = RegistryProtocol.RANK_UNKNOWN;
    private Server server;
    private ConfigReplicaClient replication;
    // The reason last logged for not serving, so a long wait logs once instead of every retry
    private String waitingFor;

    private ConfigApplication(ConfigService service, ConfigServer handler) {
        this.service = service;
        this.handler = handler;
    }

    public static void main(String[] args) throws InterruptedException {
        ConfigDatabase database = ConfigDatabase.fromEnvironment();
        // Without its tables there is nothing to serve
        database.migrate();
        ConfigService service = new ConfigService(new ConfigStore(database.dataSource()), Clock.systemUTC());
        service.refresh();

        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("config-refresh", true));
        refresher.scheduleWithFixedDelay(() -> {
            try {
                service.refresh();
            } catch (RuntimeException e) {
                LOGGER.warn("Cannot refresh configurations: {}", e.getMessage());
            }
        }, REFRESH_INTERVAL.toMillis(), REFRESH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        ExecutorService workers = Executors.newFixedThreadPool(4, new DefaultThreadFactory("config-worker", true));
        ConfigApplication application = new ConfigApplication(service, new ConfigServer(service, new RegistryVerifier(),
                workers));
        Runtime.getRuntime().addShutdownHook(new Thread(database::close, "config-shutdown"));
        application.start();
    }

    private void start() {
        rank = archon.initialize(GROUP, NAME, this);
        LOGGER.info("Registered as {} / {} with rank {}", GROUP, NAME, rank);
        roles.execute(this::applyRole);
    }

    @Override
    public void onRankUpdateMessage(int newRank) {
        int previous = rank;
        rank = newRank;
        if (previous != newRank && previous != RegistryProtocol.RANK_UNKNOWN) {
            LOGGER.info("Rank changed from {} to {}", previous, newRank);
            roles.execute(this::applyRole);
        }
    }

    // Runs on the role thread
    private void stopReplicating() {
        if (replication != null) {
            replication.close();
            replication = null;
        }
    }

    // Runs on the role thread
    private void applyRole() {
        try {
            if (rank == RegistryProtocol.RANK_PRIMARY) {
                if (server == null) {
                    stopReplicating();
                    // Whatever the old primary wrote but never pushed is in the database
                    service.refresh();
                    server = archon.createService(SERVICE, handler);
                    waitingFor = null;
                    LOGGER.info("--------------- PRIMARY: serving {} ---------------", SERVICE);
                }
            } else {
                if (server != null) {
                    archon.stopService(SERVICE);
                    server = null;
                }
                if (replication == null) {
                    replication = archon.replicateConfigs(service);
                    LOGGER.info("--------------- MIRROR: replicating from the primary ---------------");
                }
            }
        } catch (ServiceException e) {
            // The registry may not know the service yet, or the port is still in use after a failover
            if (!String.valueOf(e.getMessage()).equals(waitingFor)) {
                waitingFor = String.valueOf(e.getMessage());
                LOGGER.warn("Cannot serve {} yet: {}. Retrying every {} ms", SERVICE, e.getMessage(), RETRY_MILLIS);
            }
            roles.schedule(this::applyRole, RETRY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            LOGGER.error("Role change failed. Retrying in {} ms", RETRY_MILLIS, e);
            roles.schedule(this::applyRole, RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}
