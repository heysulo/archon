package dev.heysulo.archon.registry;

import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.server.RegistryServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainApplication {
    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);

    public static void main(String[] args) throws InterruptedException {
        logger.info("Starting Archon Registry...");
        MainApplication application = new MainApplication();
        try {
            application.start();
        } catch (Exception e) {
            logger.error("Error starting Archon Registry", e);
        }
    }

    private void start() throws InterruptedException {
        RegistryServer registryServer = RegistryServer.getInstance();
        registryServer.initialize();
        LeaderElectorClient.getInstance().start();
        registryServer.start();
        logger.info("Archon Registry startup complete.");
    }
}