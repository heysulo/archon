package dev.heysulo.archon.registry.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether this registry is ready, as Kubernetes' readiness probe sees it: a file that exists while ready. A registry
 * is ready as the primary, or as a mirror holding the primary's complete state. A rolling restart waits for each
 * restarted registry to be ready before restarting the next, so a registry that becomes primary always knows every
 * application, and does not refuse them when they reconnect.
 *
 * <p>The file is {@code ARCHON_READY_FILE}, or {@value #DEFAULT_FILE}.
 */
public final class RegistryReadiness {
    static final String FILE_ENV = "ARCHON_READY_FILE";
    static final String DEFAULT_FILE = "/tmp/archon-registry-ready";
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryReadiness.class);
    private static volatile Path file = fromEnvironment();
    private static volatile Boolean ready;

    private RegistryReadiness() {
    }

    /** Ready: primary, or a mirror with the primary's complete state. */
    public static synchronized void ready(String why) {
        if (Boolean.TRUE.equals(ready)) {
            return;
        }
        ready = true;
        try {
            Files.writeString(file, why);
            LOGGER.info("Ready: {}", why);
        } catch (IOException e) {
            LOGGER.warn("Ready ({}), but cannot write {}: {}", why, file, e.getMessage());
        }
    }

    /** Not ready, as while starting, syncing or electing a primary. */
    public static synchronized void notReady(String why) {
        if (Boolean.FALSE.equals(ready)) {
            return;
        }
        ready = false;
        try {
            Files.deleteIfExists(file);
            LOGGER.info("Not ready: {}", why);
        } catch (IOException e) {
            LOGGER.warn("Not ready ({}), but cannot remove {}: {}", why, file, e.getMessage());
        }
    }

    public static boolean isReady() {
        return Boolean.TRUE.equals(ready);
    }

    static synchronized void useFile(Path path) {
        file = path;
        ready = null;
    }

    private static Path fromEnvironment() {
        String configured = System.getenv(FILE_ENV);
        return Path.of(configured == null || configured.isBlank() ? DEFAULT_FILE : configured.trim());
    }
}
