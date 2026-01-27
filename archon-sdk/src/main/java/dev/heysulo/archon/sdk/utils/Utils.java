package dev.heysulo.archon.sdk.utils;

import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public class Utils {
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
