package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ProcessListRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessListResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;
import dev.heysulo.archon.dictionary.sdk.services.ProcessInfo;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;

public class ProcessListTest {

    private RegistryServer server;

    @BeforeClass
    public void actAsPrimary() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    // The portal connects without registering, so the primary answers on an unregistered connection
    @Test
    public void listsEveryKnownProcessInOrder() {
        FakeConnection app = new FakeConnection();
        ApplicationRegistrationMessage registration = new ApplicationRegistrationMessage("PortalTestApp", "Portal", "TBD");
        registration.setRank(RANK_UNKNOWN);
        server.onMessage(null, app, registration);
        server.onMessage(null, new FakeConnection(), registration);

        FakeConnection portal = new FakeConnection();
        server.onMessage(null, portal, new ProcessListRequest(42));

        ProcessListResponse response = portal.last(ProcessListResponse.class);
        Assert.assertEquals(response.getRequestId(), 42);
        Assert.assertEquals(response.getTerm(), RegistryServer.getTerm());
        var mine = response.getProcesses().stream()
                .filter(p -> p.group().equals("Portal") && p.name().equals("PortalTestApp")).toList();
        Assert.assertEquals(mine.stream().map(ProcessInfo::rank).toList(), java.util.List.of(1, 2));
        Assert.assertTrue(mine.get(0).connected());
        Assert.assertEquals(mine.get(0).runLevel(), "STARTING");
        // This registration came without details, as from an older SDK: the connection's address stands in
        Assert.assertEquals(mine.get(0).details().address(), "fake");
        Assert.assertNull(mine.get(0).details().host());
    }

    @Test
    public void reportsWhereEachInstanceSaysItRuns() {
        ApplicationRegistrationMessage registration = new ApplicationRegistrationMessage("DetailedApp", "Portal", "TBD");
        registration.setRank(RANK_UNKNOWN);
        registration.setDetails(new InstanceDetails("detailed-app-5c7d9", "192.168.1.20", "node-a"));
        server.onMessage(null, new FakeConnection(), registration);

        FakeConnection portal = new FakeConnection();
        server.onMessage(null, portal, new ProcessListRequest(7));

        ProcessInfo detailed = portal.last(ProcessListResponse.class).getProcesses().stream()
                .filter(p -> p.name().equals("DetailedApp")).findFirst().orElseThrow();
        Assert.assertEquals(detailed.details(), new InstanceDetails("detailed-app-5c7d9", "192.168.1.20", "node-a"));
    }
}
