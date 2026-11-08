package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessListResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessWatchRequest;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;

public class ProcessWatchersTest {

    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    @Test
    public void aWatcherGetsTheListAtOnceAndAgainOnEveryChange() throws Exception {
        FakeConnection portal = new FakeConnection();
        server.onMessage(null, portal, new ProcessWatchRequest());
        awaitTrue(() -> portal.last(ProcessListResponse.class) != null);
        ProcessListResponse first = portal.last(ProcessListResponse.class);
        Assert.assertEquals(first.getRequestId(), ProcessWatchRequest.PUSHED);

        ApplicationRegistrationMessage registration =
                new ApplicationRegistrationMessage("WatchedApp", "Watch", "TBD");
        registration.setRank(RANK_UNKNOWN);
        server.onMessage(null, new FakeConnection(), registration);

        awaitTrue(() -> portal.last(ProcessListResponse.class) != first
                && portal.last(ProcessListResponse.class).getProcesses().stream()
                        .anyMatch(p -> p.name().equals("WatchedApp")));
    }

    @Test
    public void aClosedWatcherIsDropped() throws Exception {
        FakeConnection portal = new FakeConnection();
        server.onMessage(null, portal, new ProcessWatchRequest());
        awaitTrue(() -> portal.last(ProcessListResponse.class) != null);
        int before = ProcessWatchers.getInstance().size();

        portal.disconnect();
        ProcessWatchers.getInstance().changed();

        awaitTrue(() -> ProcessWatchers.getInstance().size() == before - 1);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            Assert.assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }
}
