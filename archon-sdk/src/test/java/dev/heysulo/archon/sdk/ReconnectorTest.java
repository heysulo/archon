package dev.heysulo.archon.sdk;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ReconnectorTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final Deque<String> primaries = new ArrayDeque<>();
    private final List<String> events = new ArrayList<>();
    private boolean refuseConnections;
    private boolean connected;
    private Reconnector reconnector;

    @BeforeMethod
    public void setUp() {
        scheduler.tasks.clear();
        primaries.clear();
        events.clear();
        refuseConnections = false;
        connected = false;
        reconnector = new Reconnector(() -> primaries.isEmpty() ? null : primaries.peek(), location -> {
            events.add("connect " + location);
            if (refuseConnections) {
                throw new IllegalStateException("refused");
            }
            connected = true;
        }, () -> events.add("re-register"), () -> connected, scheduler, 1_000);
    }

    // Losing the connection is reported on a network thread, which must return at once
    @Test
    public void reconnectingRunsOnTheScheduler() {
        primaries.add("10.21.3.201");
        reconnector.connectionLost();
        Assert.assertTrue(events.isEmpty(), "nothing ran on the calling thread");

        scheduler.runPending();
        Assert.assertEquals(events, List.of("connect 10.21.3.201", "re-register"));
        Assert.assertFalse(reconnector.isReconnecting());
    }

    @Test
    public void keepsLookingUntilAPrimaryAnswers() {
        reconnector.connectionLost();
        scheduler.runPending();
        scheduler.runPending();
        Assert.assertTrue(events.isEmpty());
        Assert.assertTrue(reconnector.isReconnecting());

        primaries.add("10.21.3.202");
        scheduler.runPending();
        Assert.assertEquals(events, List.of("connect 10.21.3.202", "re-register"));
    }

    @Test
    public void retriesWhenTheConnectionFails() {
        primaries.add("10.21.3.201");
        refuseConnections = true;
        reconnector.connectionLost();
        scheduler.runPending();
        Assert.assertEquals(events, List.of("connect 10.21.3.201"));
        Assert.assertEquals(scheduler.tasks.size(), 1, "a retry is scheduled");

        refuseConnections = false;
        scheduler.runPending();
        Assert.assertEquals(events.get(events.size() - 1), "re-register");
    }

    @Test
    public void reportsOfTheSameLossStartOneReconnect() {
        primaries.add("10.21.3.201");
        reconnector.connectionLost();
        reconnector.connectionLost();
        Assert.assertEquals(scheduler.tasks.size(), 1);
    }

    // A drop while re-registering was ignored as a duplicate, so the reconnector checks once more at the end
    @Test
    public void reconnectsAgainIfTheConnectionDroppedWhileReRegistering() {
        primaries.add("10.21.3.201");
        reconnector = new Reconnector(primaries::peek, location -> connected = true, () -> {
            events.add("re-register");
            connected = false;
            reconnector.connectionLost();
        }, () -> connected, scheduler, 1_000);

        reconnector.connectionLost();
        scheduler.runPending();
        Assert.assertEquals(scheduler.tasks.size(), 1, "a fresh reconnect is queued");
    }

    @Test
    public void stopsForGoodAfterATermination() {
        reconnector.connectionLost();
        reconnector.stop();
        primaries.add("10.21.3.201");
        scheduler.runPending();
        reconnector.connectionLost();
        scheduler.runPending();
        Assert.assertTrue(events.isEmpty());
        Assert.assertFalse(reconnector.isReconnecting());
    }

    /** Holds scheduled tasks until the test runs them. */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            tasks.add(task);
            return null;
        }

        void runPending() {
            List<Runnable> due = new ArrayList<>(tasks);
            tasks.clear();
            due.forEach(Runnable::run);
        }
    }
}
