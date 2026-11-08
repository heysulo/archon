package dev.heysulo.archon.sdk.services;

import dev.heysulo.databridge.core.common.ProtocolSettings;

import java.util.function.Consumer;

/** How a {@link ServiceClient} connects. Immutable; each {@code with} method returns a changed copy. */
public final class ServiceClientOptions {
    private static final ServiceClientOptions DEFAULTS = new ServiceClientOptions(0, settings -> { });

    private final int warmupRoundTrips;
    private final Consumer<ProtocolSettings> settings;

    private ServiceClientOptions(int warmupRoundTrips, Consumer<ProtocolSettings> settings) {
        this.warmupRoundTrips = warmupRoundTrips;
        this.settings = settings;
    }

    /** No warm-up, default connection settings. */
    public static ServiceClientOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Sends this many round trips over every new connection, first or after a reconnect, before reporting it ready.
     * A JVM only compiles code that runs often, so a client that sends rarely otherwise pays for interpreted code on
     * both ends: measured at about 1.7 ms a round trip cold against 0.4 ms warm. A few thousand is enough. The service
     * must run an SDK with warm-up support, which answers these without involving the application.
     */
    public ServiceClientOptions withWarmup(int roundTrips) {
        if (roundTrips < 0) {
            throw new IllegalArgumentException("Warm-up round trips must not be negative: " + roundTrips);
        }
        return new ServiceClientOptions(roundTrips, settings);
    }

    /** Adjusts each connection's settings, for example trusted packages, before it connects. */
    public ServiceClientOptions withSettings(Consumer<ProtocolSettings> settings) {
        return new ServiceClientOptions(warmupRoundTrips, settings);
    }

    public int getWarmupRoundTrips() {
        return warmupRoundTrips;
    }

    public Consumer<ProtocolSettings> getSettings() {
        return settings;
    }
}
