package dev.heysulo.archon.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reconnects to the primary registry after the connection drops, on its own thread. Finding a primary and
 * connecting both block, which a network thread must never do.
 */
final class Reconnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(Reconnector.class);

    private final Supplier<String> findPrimary;
    private final Consumer<String> connect;
    private final Runnable afterConnect;
    private final BooleanSupplier connected;
    private final ScheduledExecutorService scheduler;
    private final long retryDelayMillis;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean stopped;

    /**
     * @param findPrimary  returns the primary registry's location, or null if none answers. Called repeatedly.
     * @param connect      connects to a location, throwing a runtime exception if it cannot
     * @param afterConnect re-registers and restores state once connected
     * @param connected    whether the connection is currently up
     */
    Reconnector(Supplier<String> findPrimary, Consumer<String> connect, Runnable afterConnect,
            BooleanSupplier connected, ScheduledExecutorService scheduler, long retryDelayMillis) {
        this.findPrimary = findPrimary;
        this.connect = connect;
        this.afterConnect = afterConnect;
        this.connected = connected;
        this.scheduler = scheduler;
        this.retryDelayMillis = retryDelayMillis;
    }

    /** Starts reconnecting, unless already reconnecting or stopped. Safe to call from a network thread. */
    void connectionLost() {
        if (!stopped && reconnecting.compareAndSet(false, true)) {
            scheduler.execute(this::attempt);
        }
    }

    /** Stops reconnecting for good, as after the registry terminated this instance. */
    void stop() {
        stopped = true;
    }

    boolean isReconnecting() {
        return reconnecting.get();
    }

    private void attempt() {
        if (stopped) {
            reconnecting.set(false);
            return;
        }
        try {
            String location = findPrimary.get();
            if (location == null) {
                retry("no primary registry answered");
                return;
            }
            connect.accept(location);
            afterConnect.run();
            LOGGER.info("Reconnected to the primary registry at {}", location);
        } catch (RuntimeException e) {
            retry(e.getMessage());
            return;
        }
        reconnecting.set(false);
        // A drop while re-registering found reconnecting still set and was ignored, so check once more
        if (!connected.getAsBoolean()) {
            connectionLost();
        }
    }

    private void retry(String why) {
        if (stopped) {
            reconnecting.set(false);
            return;
        }
        LOGGER.info("Reconnecting to the registry failed: {}. Retrying in {} ms", why, retryDelayMillis);
        scheduler.schedule(this::attempt, retryDelayMillis, TimeUnit.MILLISECONDS);
    }
}
