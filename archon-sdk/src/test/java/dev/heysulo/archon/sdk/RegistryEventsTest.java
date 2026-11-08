package dev.heysulo.archon.sdk;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RegistryEventsTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private volatile Thread callbackThread;

    @BeforeMethod
    public void setUp() {
        calls.clear();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterMethod
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void confirmsARankOnlyAfterTheCallbackReturns() throws Exception {
        RegistryEvents events = new RegistryEvents(callback(false), rank -> calls.add("confirm " + rank), executor);

        events.rankChanged(1);
        events.rankChanged(2);
        drain();

        Assert.assertEquals(calls, List.of("callback 1", "confirm 1", "callback 2", "confirm 2"));
    }

    @Test
    public void callbacksRunOffTheCallingThread() throws Exception {
        RegistryEvents events = new RegistryEvents(callback(false), rank -> { }, executor);
        events.rankChanged(3);
        drain();
        Assert.assertNotNull(callbackThread);
        Assert.assertNotEquals(callbackThread, Thread.currentThread());
    }

    @Test
    public void aFailingCallbackStillConfirmsTheRank() throws Exception {
        RegistryEvents events = new RegistryEvents(callback(true), rank -> calls.add("confirm " + rank), executor);
        events.rankChanged(4);
        drain();
        Assert.assertEquals(calls, List.of("callback 4", "confirm 4"));
    }

    @Test
    public void deliversTermination() throws Exception {
        RegistryEvents events = new RegistryEvents(callback(false), rank -> { }, executor);
        events.terminated("Rank is held by another running instance");
        drain();
        Assert.assertEquals(calls, List.of("terminated Rank is held by another running instance"));
    }

    private ArchonCallback callback(boolean fail) {
        return new ArchonCallback() {
            @Override
            public void onRankUpdateMessage(int newRank) {
                callbackThread = Thread.currentThread();
                calls.add("callback " + newRank);
                if (fail) {
                    throw new IllegalStateException("app bug");
                }
            }

            @Override
            public void onTerminated(String reason) {
                calls.add("terminated " + reason);
            }
        };
    }

    private void drain() throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
}
