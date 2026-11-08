package dev.heysulo.archon.sdk.utils;

import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Small helpers shared across the SDK. */
public final class Utils {
    private Utils() {
    }

    /**
     * Blocks, checking now and then, until a condition holds.
     *
     * @param condition     checked on the calling thread
     * @param timeoutMillis how long to wait for it
     * @param pollInterval  how long to sleep between checks, in milliseconds
     * @throws InterruptedException if interrupted while sleeping
     * @throws TimeoutException     if the condition does not hold in time
     */
    public static void waitForCondition(BooleanSupplier condition, long timeoutMillis, long pollInterval)
            throws InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                throw new TimeoutException("Condition not met within " + timeoutMillis + "ms");
            }
            Thread.sleep(pollInterval);
        }
    }
}
