package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceLookupResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStartedMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceStoppedMessage;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ServiceManagerTest {

    private static final ServiceDefinition ORDERS =
            new ServiceDefinition("orders", "Orders", 1, "Shop", "OrderApp", ServiceMode.PRIMARY_MIRROR);
    private static final ServerCallback NO_OP = new ServerCallback() {
        @Override
        public void onConnect(Server server, Connection connection) {
        }

        @Override
        public void onDisconnect(Server server, Connection connection) {
        }

        @Override
        public void onMessage(Server server, Connection connection, Message message) {
        }

        @Override
        public void onError(Server server, Connection connection, Throwable cause) {
        }
    };

    private final List<Message> sent = new ArrayList<>();
    private final List<FakeServer> servers = new ArrayList<>();
    private ServiceManager manager;
    private boolean refuseStarts;

    /** A registry that answers at once, knowing only ORDERS. */
    private final Consumer<Message> registry = message -> {
        sent.add(message);
        if (message instanceof ServiceLookupMessage lookup) {
            boolean known = lookup.getServiceId().equals(ORDERS.id());
            List<ServiceEndpoint> endpoints =
                    known ? List.of(new ServiceEndpoint("10.0.0.1", 9001, 1, true)) : List.of();
            manager.onResponse(new ServiceLookupResponseMessage(lookup.getRequestId(), lookup.getServiceId(),
                    known ? ORDERS : null, endpoints));
        } else if (message instanceof ServiceStartedMessage started && started.getRequestId() != 0) {
            manager.onResponse(refuseStarts
                    ? ServiceStartResponseMessage.refused(started.getRequestId(), started.getServiceId(), "no")
                    : ServiceStartResponseMessage.accepted(started.getRequestId(), started.getServiceId()));
        }
    };

    @BeforeMethod
    public void setUp() {
        sent.clear();
        servers.clear();
        refuseStarts = false;
        manager = owner("OrderApp");
    }

    @Test
    public void looksUpAServiceAndItsPrimary() {
        ServiceInfo info = manager.lookup("orders");
        Assert.assertEquals(info.definition(), ORDERS);
        Assert.assertEquals(info.primary().orElseThrow().host(), "10.0.0.1");
    }

    @Test
    public void anUnknownServiceFailsTheLookup() {
        Assert.expectThrows(ServiceException.class, () -> manager.lookup("billing"));
    }

    @Test
    public void startsOnTheResolvedPortAndAnnouncesIt() {
        manager.start("orders", NO_OP, server -> { });

        Assert.assertEquals(servers.size(), 1);
        Assert.assertTrue(servers.get(0).started);
        Assert.assertEquals(servers.get(0).port, 9001);
        ServiceStartedMessage announced = last(ServiceStartedMessage.class);
        Assert.assertEquals(announced.getPort(), 9001);
        Assert.assertEquals(announced.getHost(), "10.0.0.7");
        Assert.assertTrue(manager.isRunning("orders"));
    }

    @Test
    public void anotherApplicationCannotStartTheService() {
        manager = owner("SearchApp");
        ServiceException e = Assert.expectThrows(ServiceException.class,
                () -> manager.start("orders", NO_OP, server -> { }));
        Assert.assertTrue(e.getMessage().contains("belongs to Shop:OrderApp"), e.getMessage());
        Assert.assertTrue(servers.isEmpty());
    }

    @Test
    public void aRefusedServiceIsStoppedAgain() {
        refuseStarts = true;
        Assert.expectThrows(ServiceException.class, () -> manager.start("orders", NO_OP, server -> { }));
        Assert.assertTrue(servers.get(0).stopped);
        Assert.assertFalse(manager.isRunning("orders"));
    }

    @Test
    public void aServiceCannotStartTwice() {
        manager.start("orders", NO_OP, server -> { });
        Assert.expectThrows(ServiceException.class, () -> manager.start("orders", NO_OP, server -> { }));
    }

    @Test
    public void stoppingClosesTheServerAndTellsTheRegistry() {
        manager.start("orders", NO_OP, server -> { });
        manager.stop("orders");

        Assert.assertTrue(servers.get(0).stopped);
        Assert.assertEquals(last(ServiceStoppedMessage.class).getServiceId(), "orders");
        Assert.assertFalse(manager.isRunning("orders"));
    }

    @Test
    public void runningServicesAreAnnouncedAgainAfterReconnecting() {
        manager.start("orders", NO_OP, server -> { });
        sent.clear();

        manager.announceAll();

        ServiceStartedMessage announced = last(ServiceStartedMessage.class);
        Assert.assertEquals(announced.getRequestId(), PendingRequests.UNTRACKED);
        Assert.assertEquals(announced.getPort(), 9001);
    }

    @Test
    public void aServiceRefusedAfterReconnectingIsStopped() {
        manager.start("orders", NO_OP, server -> { });
        manager.onResponse(ServiceStartResponseMessage.refused(PendingRequests.UNTRACKED, "orders", "gone"));

        Assert.assertTrue(servers.get(0).stopped);
        Assert.assertFalse(manager.isRunning("orders"));
    }

    private ServiceManager owner(String application) {
        return new ServiceManager("Shop", application, registry, definition -> 9000 + definition.portOffset(),
                (port, callback) -> {
                    FakeServer server = new FakeServer(port, callback);
                    servers.add(server);
                    return server;
                }, () -> "10.0.0.7");
    }

    private <T extends Message> T last(Class<T> type) {
        return sent.stream().filter(type::isInstance).map(type::cast).reduce((a, b) -> b).orElseThrow();
    }

    private static final class FakeServer extends Server {
        private final int port;
        private boolean started;
        private boolean stopped;

        FakeServer(int port, ServerCallback callback) {
            super(port, callback);
            this.port = port;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
