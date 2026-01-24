package dev.heysulo.archon.registry;

import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.server.RegistryServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class MainApplication {
    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);
    EventLoopGroup bossGroup = new NioEventLoopGroup();
    EventLoopGroup workerGroup = new NioEventLoopGroup();

    public static void main(String[] args) throws InterruptedException {
        logger.info("Starting Archon Registry...");
        // Some randomness
        long sleepTime = ThreadLocalRandom.current().nextInt(1, 16);
        logger.info("Sleeping for {} seconds...", sleepTime);
        Thread.sleep(sleepTime * 1_000);
        MainApplication application = new MainApplication();
        try {
            application.start();
        } catch (Exception e) {
            logger.error("Error starting Archon Registry", e);
        }
        logger.info("Archon Registry stopped.");
    }

    private void start() throws InterruptedException {
        RegistryServer registryServer = RegistryServer.buildInstance(bossGroup, workerGroup);
        registryServer.initialize();
        LeaderElectorClient leaderElectorClient = new LeaderElectorClient(workerGroup);
        leaderElectorClient.start();
        registryServer.start();
        logger.info("Archon Registry startup complete.");
        try {
            bossGroup.terminationFuture().sync();
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for registryServer termination", e);
            Thread.currentThread().interrupt();
        }
    }
}