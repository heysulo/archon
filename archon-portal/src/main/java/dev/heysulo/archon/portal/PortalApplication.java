package dev.heysulo.archon.portal;

import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.AuthStore;
import dev.heysulo.archon.portal.auth.PasswordHasher;
import dev.heysulo.archon.portal.db.PortalDatabase;
import dev.heysulo.archon.portal.services.ServiceStore;
import dev.heysulo.archon.sdk.ArchonInterface;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Archon's web portal. Shows what the primary registry knows; for now, the running processes. Configured with
 * {@code ARCHON_REGISTRY_LOCATIONS}, like applications, {@code PORTAL_PORT} (default 8080), and the archon schema's
 * {@code ARCHON_DB_URL}, {@code ARCHON_DB_USER} and {@code ARCHON_DB_PASSWORD}, where users and sessions live.
 *
 * <p>While no users exist, each replica logs a one-time setup token for creating the first admin at /setup.
 *
 * <p>Run any number of replicas. Each registers as {@code Archon / Portal}, so the registry ranks them as one
 * primary and any number of mirrors, and they appear on the page like every other process.
 */
public final class PortalApplication {
    static final String PORT_ENV = "PORTAL_PORT";
    static final int DEFAULT_PORT = 8080;
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalApplication.class);
    private static final long CLEAN_UP_MINUTES = 10;

    private PortalApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = port(System.getenv(PORT_ENV));
        PortalDatabase database = PortalDatabase.fromEnvironment();
        // The portal cannot sign anyone in without its tables, so it does not start without them
        database.migrate();
        AuthService auth = new AuthService(new AuthStore(database.dataSource()), new PasswordHasher(),
                Clock.systemUTC());
        announceSetup(auth);
        scheduleCleanUp(auth);

        PortalIdentity identity = new PortalIdentity();
        RegistryWatcher registry = new RegistryWatcher();
        PortalConfigs configs = new PortalConfigs();
        PortalServer server = new PortalServer(registry, snapshot -> snapshot.servedBy(identity), "/portal",
                new AuthApi(auth), new ConfigApi(configs, auth::authenticate),
                new ServicesApi(new ServiceStore(database.dataSource()), registry, auth::authenticate),
                auth::authenticate);
        // Serving first: the page reports registry trouble itself, and registering waits until a primary answers
        server.start(port);
        registry.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            database.close();
        }, "portal-shutdown"));
        Thread registration = new Thread(() -> register(identity, configs), "portal-registration");
        registration.setDaemon(true);
        registration.start();
        LOGGER.info("Archon portal started on {}", identity.getHost());
    }

    // The token is the only way to create the first admin, so it goes to the log, which only operators can read
    private static void announceSetup(AuthService auth) {
        auth.createSetupToken().ifPresent(token -> LOGGER.warn(
                "No portal users exist yet. Open the portal and create the first admin at /setup with setup "
                        + "token: {}  (valid for 24 hours, any portal replica accepts it)", token));
    }

    private static void scheduleCleanUp(AuthService auth) {
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("portal-auth-clean-up", true));
        cleaner.scheduleWithFixedDelay(() -> {
            try {
                int removed = auth.cleanUp();
                if (removed > 0) {
                    LOGGER.info("Removed {} expired sessions and setup tokens", removed);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Session clean-up failed: {}", e.getMessage());
            }
        }, CLEAN_UP_MINUTES, CLEAN_UP_MINUTES, TimeUnit.MINUTES);
    }

    private static void register(PortalIdentity identity, PortalConfigs configs) {
        try {
            ArchonInterface archon = ArchonInterface.getInstance();
            int rank = archon.initialize(PortalIdentity.GROUP, PortalIdentity.NAME, identity);
            LOGGER.info("Registered as {} / {} with rank {}", PortalIdentity.GROUP, PortalIdentity.NAME, rank);
            configs.start(archon.replicateConfigs(configs.store()));
        } catch (RuntimeException e) {
            // Without a rank the replica still serves; exiting lets the orchestrator start a fresh one to retry
            LOGGER.error("Could not register the portal: {}. Exiting", e.getMessage());
            System.exit(1);
        }
    }

    static int port(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(configured.trim());
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Reported below
        }
        throw new IllegalArgumentException(PORT_ENV + " is not a valid port: " + configured);
    }
}
