package dev.heysulo.archon.sdk.constant;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;

import java.time.Duration;
import java.util.List;

public final class RegistryConstants {
    public static final int PORT = RegistryProtocol.PORT;
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(3);
    // A registry that has gone away often leaves connection attempts unanswered; fail fast and try the next one
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private RegistryConstants() {
    }

    public static List<String> getRegistryLocations() {
        return RegistryProtocol.getRegistryLocations();
    }
}
