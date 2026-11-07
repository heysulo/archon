package dev.heysulo.archon.dictionary.sdk;

import java.util.Arrays;
import java.util.List;

/**
 * Values that registries and applications must agree on.
 */
public final class RegistryProtocol {
    public static final int PORT = 8080;
    public static final int RANK_PRIMARY = 1;
    public static final int RANK_UNKNOWN = -1;

    private static final String REGISTRY_LOCATIONS_ENV = "ARCHON_REGISTRY_LOCATIONS";
    private static final String LOCATION_SEPARATOR = "\\|";

    private RegistryProtocol() {
    }

    public static List<String> getRegistryLocations() {
        return parseLocations(System.getenv(REGISTRY_LOCATIONS_ENV));
    }

    static List<String> parseLocations(String locations) {
        return locations == null ? List.of() : Arrays.asList(locations.split(LOCATION_SEPARATOR));
    }
}
