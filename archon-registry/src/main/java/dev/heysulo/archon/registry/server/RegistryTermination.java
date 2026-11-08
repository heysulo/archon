package dev.heysulo.archon.registry.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ends a primary that lost the vote to another primary. Its orchestrator restarts it as a fresh registry. */
final class RegistryTermination {
    static final int EXIT_CODE = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryTermination.class);

    private RegistryTermination() {
    }

    static void terminate(String reason) {
        LOGGER.error("Terminating registry. {}", reason);
        System.exit(EXIT_CODE);
    }
}
