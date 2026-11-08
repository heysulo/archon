package dev.heysulo.archon.sdk.constant;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;

import java.time.Duration;
import java.util.List;

/** How the SDK reaches the registries. */
public final class RegistryConstants {
    /** The port every registry listens on for applications. */
    public static final int PORT = RegistryProtocol.PORT;
    /**
     * How often the registry connection sends a heartbeat when idle. A registry that misses two in a row is treated
     * as gone, and the SDK looks for the primary again.
     */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(3);
    /**
     * How long a connection attempt to one registry may take. A registry that has gone away often leaves attempts
     * unanswered, so the SDK gives up quickly and tries the next one.
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private RegistryConstants() {
    }

    /**
     * The registries to look for the primary among, from {@code ARCHON_REGISTRY_LOCATIONS}: addresses separated by
     * {@code |}, such as {@code 10.21.3.201|10.21.3.202|10.21.3.203}.
     *
     * @return the registries' addresses, in the order given
     */
    public static List<String> getRegistryLocations() {
        return RegistryProtocol.getRegistryLocations();
    }
}
