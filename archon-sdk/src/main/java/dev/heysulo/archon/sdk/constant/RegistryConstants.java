package dev.heysulo.archon.sdk.constant;

import java.util.Arrays;
import java.util.List;

public class RegistryConstants {
    public static final int PORT = 8080;
    public static List<String> getRegistryLocations() {
        String hosts = System.getenv("ARCHON_REGISTRY_LOCATIONS");
        if (hosts == null) {
            return List.of();
        }
        return Arrays.asList(hosts.split("\\|"));
    }
}
