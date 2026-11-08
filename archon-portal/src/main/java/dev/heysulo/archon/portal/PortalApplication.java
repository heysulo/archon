package dev.heysulo.archon.portal;

import dev.heysulo.archon.sdk.ArchonInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Archon's web portal. Shows what the primary registry knows; for now, the running processes. Configured with
 * {@code ARCHON_REGISTRY_LOCATIONS}, like applications, and {@code PORTAL_PORT} (default 8080).
 *
 * <p>Run any number of replicas. Each registers as {@code Archon / Portal}, so the registry ranks them as one
 * primary and any number of mirrors, and they appear on the page like every other process.
 */
public final class PortalApplication {
    static final String PORT_ENV = "PORTAL_PORT";
    static final int DEFAULT_PORT = 8080;
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalApplication.class);

    private PortalApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = port(System.getenv(PORT_ENV));
        PortalIdentity identity = new PortalIdentity();
        RegistryWatcher registry = new RegistryWatcher();
        PortalServer server = new PortalServer(registry, snapshot -> snapshot.servedBy(identity), "/portal");
        // Serving first: the page reports registry trouble itself, and registering waits until a primary answers
        server.start(port);
        registry.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "portal-shutdown"));
        Thread registration = new Thread(() -> register(identity), "portal-registration");
        registration.setDaemon(true);
        registration.start();
        LOGGER.info("Archon portal started on {}", identity.getHost());
    }

    private static void register(PortalIdentity identity) {
        try {
            int rank = ArchonInterface.getInstance().initialize(PortalIdentity.GROUP, PortalIdentity.NAME, identity);
            LOGGER.info("Registered as {} / {} with rank {}", PortalIdentity.GROUP, PortalIdentity.NAME, rank);
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
