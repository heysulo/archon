package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import dev.heysulo.archon.dictionary.sdk.messages.LogBatch;
import dev.heysulo.archon.dictionary.sdk.messages.LogStreamControl;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessLogBatch;
import dev.heysulo.archon.registry.applications.Application;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

public class LogRelaysTest {
    private final LogRelays relays = LogRelays.getInstance();
    private Application app;
    private FakeConnection appConnection;

    @BeforeMethod
    public void runningInstance() {
        relays.clear();
        app = new Application("Shop", "Logs", 1);
        appConnection = new FakeConnection();
        app.setConnection(appConnection);
        app.setRunLevel(RunLevel.RUNNING);
    }

    @AfterMethod
    public void clear() {
        relays.clear();
    }

    private long controls(boolean streaming) {
        return appConnection.sent(LogStreamControl.class).stream()
                .filter(control -> control.isStreaming() == streaming).count();
    }

    @Test
    public void asksTheInstanceOnceWhateverTheWatchers() {
        FakeConnection first = new FakeConnection();
        FakeConnection second = new FakeConnection();
        relays.watch(first, app);
        relays.watch(second, app);
        Assert.assertEquals(controls(true), 1);

        relays.fromInstance(app, new LogBatch(List.of(new LogLine(1, "INFO", "", "", "hello")), 0, false));
        Assert.assertEquals(first.last(ProcessLogBatch.class).getLines().get(0).message(), "hello");
        Assert.assertEquals(second.last(ProcessLogBatch.class).getRank(), 1);

        relays.unwatch(first, app);
        Assert.assertEquals(controls(false), 0, "someone still watches");
        relays.disconnected(second);
        Assert.assertEquals(controls(false), 1, "the last watcher left");
        Assert.assertFalse(relays.isWatched(app));
    }

    @Test
    public void stopsAnInstanceStreamingToNobody() {
        relays.fromInstance(app, new LogBatch(List.of(), 0, false));
        Assert.assertEquals(controls(false), 1);
    }

    @Test
    public void resumesAfterAReconnectAndReportsDrops() {
        FakeConnection watcher = new FakeConnection();
        relays.watch(watcher, app);
        relays.lost(app);
        Assert.assertEquals(watcher.last(ProcessLogBatch.class).getStatus(), LogRelays.DISCONNECTED);
        relays.connected(app);
        Assert.assertEquals(controls(true), 2, "asked again when it comes back");
    }

    @Test
    public void ignoresInstancesThatAreNotRunning() {
        app.setRunLevel(RunLevel.KILLED);
        relays.watch(new FakeConnection(), app);
        Assert.assertFalse(relays.isWatched(app));
        Assert.assertEquals(controls(true), 0);
    }
}
