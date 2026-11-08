package dev.heysulo.archon.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default reaction to a termination from the registry. */
final class ArchonTermination {
    static final int EXIT_CODE = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchonTermination.class);

    private ArchonTermination() {
    }

    static void exitProcess(String reason) {
        LOGGER.error("Terminated by the registry: {}. Exiting", reason);
        System.exit(EXIT_CODE);
    }
}
