package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.server.FakeConnection;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

public class ServiceRegistryTest {

    private static final List<ServiceCatalog.Row> CATALOG = List.of(
            new ServiceCatalog.Row("orders", "Orders", 1, "Shop", "OrderApp", "PRIMARY_MIRROR"),
            new ServiceCatalog.Row("search", "Search", 2, "Shop", "SearchApp", "LOAD_BALANCE"));

    private ServiceRegistry registry;

    @BeforeMethod
    public void freshRegistry() {
        registry = new ServiceRegistry();
        registry.setCatalog(ServiceCatalog.fromRows(CATALOG));
    }

    @Test
    public void anUnknownServiceIsNotFound() {
        ServiceLookupResponseMessage response = registry.lookup(new ServiceLookupMessage(4, "billing"));
        Assert.assertEquals(response.getRequestId(), 4);
        Assert.assertFalse(response.isFound());
        Assert.assertTrue(response.getEndpoints().isEmpty());
    }

    @Test
    public void aServiceNobodyRunsHasItsDefinitionAndNoEndpoints() {
        ServiceLookupResponseMessage response = registry.lookup(new ServiceLookupMessage(1, "orders"));
        Assert.assertTrue(response.isFound());
        Assert.assertEquals(response.getDefinition().portOffset(), 1);
        Assert.assertTrue(response.getEndpoints().isEmpty());
    }

    // Ownership goes by application definition alone, so a standby may start the service too
    @Test
    public void anyRankOfTheOwningApplicationMayStartTheService() {
        Assert.assertTrue(start(running("OrderApp", 3), "orders", 9001).isAccepted());
    }

    @Test
    public void anotherApplicationMayNotStartTheService() {
        ServiceStartResponseMessage response = start(running("SearchApp", 1), "orders", 9001);
        Assert.assertFalse(response.isAccepted());
        Assert.assertEquals(response.getReason(), ServiceRegistry.REASON_NOT_OWNER);
        Assert.assertTrue(endpoints("orders").isEmpty());
    }

    @Test
    public void anUnknownServiceOrBadPortIsRefused() {
        Assert.assertEquals(start(running("OrderApp", 1), "billing", 9001).getReason(),
                ServiceRegistry.REASON_UNKNOWN_SERVICE);
        Assert.assertEquals(start(running("OrderApp", 1), "orders", 0).getReason(),
                ServiceRegistry.REASON_INVALID_PORT);
    }

    @Test
    public void theLowestRankedInstanceIsPrimary() {
        start(running("OrderApp", 3), "orders", 9003);
        start(running("OrderApp", 2), "orders", 9002);

        List<ServiceEndpoint> endpoints = endpoints("orders");
        Assert.assertEquals(endpoints.size(), 2);
        Assert.assertEquals(endpoints.get(0), new ServiceEndpoint("fake", 9002, 2, true));
        Assert.assertEquals(endpoints.get(1), new ServiceEndpoint("fake", 9003, 3, false));
    }

    @Test
    public void loadBalancedInstancesAreAllPeers() {
        start(running("SearchApp", 2), "search", 9102);
        start(running("SearchApp", 1), "search", 9101);

        List<ServiceEndpoint> endpoints = endpoints("search");
        Assert.assertEquals(endpoints.stream().map(ServiceEndpoint::rank).toList(), List.of(1, 2));
        Assert.assertTrue(endpoints.stream().noneMatch(ServiceEndpoint::primary));
    }

    @Test
    public void primaryPassesOnWhenItsInstanceStopsRunning() {
        Application first = running("OrderApp", 1);
        start(first, "orders", 9001);
        start(running("OrderApp", 2), "orders", 9002);

        first.setRunLevel(RunLevel.KILLED);

        List<ServiceEndpoint> endpoints = endpoints("orders");
        Assert.assertEquals(endpoints.size(), 1);
        Assert.assertEquals(endpoints.get(0).rank(), 2);
        Assert.assertTrue(endpoints.get(0).primary());
    }

    @Test
    public void stoppedAndForgottenServicesAreRemoved() {
        Application orders = running("OrderApp", 1);
        Application search = running("SearchApp", 1);
        start(orders, "orders", 9001);
        start(search, "search", 9101);

        registry.stop(orders, new ServiceStoppedMessage("orders"));
        registry.forget(search);

        Assert.assertTrue(endpoints("orders").isEmpty());
        Assert.assertTrue(endpoints("search").isEmpty());
    }

    @Test
    public void startingAgainReplacesThePort() {
        Application orders = running("OrderApp", 1);
        start(orders, "orders", 9001);
        start(orders, "orders", 9011);
        Assert.assertEquals(endpoints("orders").stream().map(ServiceEndpoint::port).toList(), List.of(9011));
    }

    private ServiceStartResponseMessage start(Application application, String serviceId, int port) {
        return registry.start(application, new ServiceStartedMessage(7, serviceId, null, port));
    }

    // Behind a load balancer the registry sees the node's address, so the instance's own one wins
    @Test
    public void anAdvertisedHostReplacesTheAddressTheRegistrySees() {
        registry.start(running("OrderApp", 1), new ServiceStartedMessage(7, "orders", "192.168.0.9", 9001));
        Assert.assertEquals(endpoints("orders").get(0).host(), "192.168.0.9");
    }

    private List<ServiceEndpoint> endpoints(String serviceId) {
        return registry.lookup(new ServiceLookupMessage(1, serviceId)).getEndpoints();
    }

    private static Application running(String name, int rank) {
        Application application = new Application("Shop", name, rank);
        application.setConnection(new FakeConnection());
        application.setRunLevel(RunLevel.RUNNING);
        return application;
    }
}
