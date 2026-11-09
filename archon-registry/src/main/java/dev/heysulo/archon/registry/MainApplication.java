package dev.heysulo.archon.registry;

import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.archon.registry.applications.ApplicationDefinitions;
import dev.heysulo.archon.registry.db.Database;
import dev.heysulo.archon.registry.services.ServiceCatalog;
import dev.heysulo.archon.registry.services.ServiceCatalogWatcher;
import dev.heysulo.archon.registry.services.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

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
        Optional<Database> database = Database.fromEnvironment();
        if (database.isPresent()) {
            ServiceRegistry.getInstance().setCatalog(ServiceCatalog.load(database.get()));
            try {
                ApplicationDefinitions.prepare(database.get());
                ApplicationDefinitions.setCurrent(ApplicationDefinitions.load(database.get()));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Cannot read application definitions: " + e.getMessage(), e);
            }
            LOGGER.info("{} applications are defined", ApplicationDefinitions.current().keys().size());
            try (java.sql.Connection connection = database.get().connect()) {
                // The portal creates join tokens here whether TLS is on yet or not
                dev.heysulo.archon.registry.tls.JoinTokenStore.prepare(connection);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Cannot prepare the join tokens table: " + e.getMessage(), e);
            }
            new ServiceCatalogWatcher(database.get(), ServiceRegistry.getInstance()).start();
        } else {
            LOGGER.warn("{} is not set. No services are defined, and every application may register",
                    Database.URL_ENV);
        }
        // Before the server starts, which serves with this registry's certificate
        dev.heysulo.archon.registry.tls.RegistryTls.fromEnvironment(database);
        RegistryServer registryServer = RegistryServer.getInstance();
        registryServer.initialize();
        LeaderElectorClient.getInstance().start(Constants.REGISTRY_LEADER_ELECTION_MAX_CONNECTION_ATTEMPTS);
        registryServer.start();
        LOGGER.info("Archon Registry startup complete.");
    }
}