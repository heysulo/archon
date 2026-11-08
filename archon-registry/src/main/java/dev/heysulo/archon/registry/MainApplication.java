package dev.heysulo.archon.registry;

import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.archon.registry.services.ServiceCatalog;
import dev.heysulo.archon.registry.services.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class MainApplication {
    static final String LOG_LEVEL_ENV = "ARCHON_LOG_LEVEL";
    private static final String SIMPLE_LOGGER_LEVEL = "org.slf4j.simpleLogger.defaultLogLevel";

    // Runs before the first logger is created, which is when slf4j-simple reads its level
    static {
        String level = System.getenv(LOG_LEVEL_ENV);
        if (level != null && !level.isBlank() && System.getProperty(SIMPLE_LOGGER_LEVEL) == null) {
            System.setProperty(SIMPLE_LOGGER_LEVEL, level.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MainApplication.class);

    public static void main(String[] args) throws InterruptedException {
        LOGGER.info("Starting Archon Registry...");
        MainApplication application = new MainApplication();
        try {
            application.start();
        } catch (Exception e) {
            LOGGER.error("Error starting Archon Registry", e);
        }
    }

    private void start() throws InterruptedException {
        // Loaded first: a registry that cannot read its service definitions must not join the cluster
        ServiceRegistry.getInstance().setCatalog(ServiceCatalog.fromEnvironment());
        RegistryServer registryServer = RegistryServer.getInstance();
        registryServer.initialize();
        LeaderElectorClient.getInstance().start(Constants.REGISTRY_LEADER_ELECTION_MAX_CONNECTION_ATTEMPTS);
        registryServer.start();
        LOGGER.info("Archon Registry startup complete.");
    }
}