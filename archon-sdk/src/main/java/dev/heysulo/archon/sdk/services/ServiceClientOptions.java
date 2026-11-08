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

    /**
     * The starting point: no warm-up, default connection settings.
     *
     * @return the default options
     */
    public static ServiceClientOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Sends this many round trips over every new connection, first or after a reconnect, before reporting it ready.
     * A JVM only compiles code that runs often, so a client that sends rarely otherwise pays for interpreted code on
     * both ends: measured at about 1.7 ms a round trip cold against 0.4 ms warm. A few thousand is enough. The service
     * must run an SDK with warm-up support, which answers these without involving the application.
     *
     * @param roundTrips how many round trips to send; 0 for none
     * @return a copy with this warm-up
     * @throws IllegalArgumentException if {@code roundTrips} is negative
     */
    public ServiceClientOptions withWarmup(int roundTrips) {
        if (roundTrips < 0) {
            throw new IllegalArgumentException("Warm-up round trips must not be negative: " + roundTrips);
        }
        return new ServiceClientOptions(roundTrips, settings);
    }

    /**
     * Adjusts each connection's settings before it connects, for example to trust the packages of the service's
     * messages or change the heartbeat. Applied to every new connection, first or after a reconnect.
     *
     * @param settings changes the settings of a connection about to be made
     * @return a copy with these settings
     */
    public ServiceClientOptions withSettings(Consumer<ProtocolSettings> settings) {
        return new ServiceClientOptions(warmupRoundTrips, settings);
    }

    /** @return how many warm-up round trips each new connection sends; 0 for none */
    public int getWarmupRoundTrips() {
        return warmupRoundTrips;
    }

    /** @return what is applied to each connection's settings before it connects */
    public Consumer<ProtocolSettings> getSettings() {
        return settings;
    }
}
