package dev.heysulo.archon.sdk.constant;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;

import java.time.Duration;
import java.util.List;

public final class RegistryConstants {
    public static final int PORT = RegistryProtocol.PORT;
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(3);

    private RegistryConstants() {
    }

    public static List<String> getRegistryLocations() {
        return RegistryProtocol.getRegistryLocations();
    }
}
