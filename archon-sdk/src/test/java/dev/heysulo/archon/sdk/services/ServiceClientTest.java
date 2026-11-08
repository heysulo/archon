package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.messages.ServiceWarmupMessage;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.dictionary.sdk.services.ServiceEndpoint;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServiceClientTest {

    private static final ServiceDefinition ORDERS =
            new ServiceDefinition("orders", "Orders", 1, "Shop", "OrderApp", ServiceMode.PRIMARY_MIRROR);
    private static final ServiceDefinition SEARCH =
            new ServiceDefinition("search", "Search", 2, "Shop", "SearchApp", ServiceMode.LOAD_BALANCE);
    private static final ServiceEndpoint FIRST = new ServiceEndpoint("10.0.0.1", 9001, 1, true);
    private static final ServiceEndpoint SECOND = new ServiceEndpoint("10.0.0.2", 9001, 2, false);
    private static final Message PING = new Message() {
    };

    private final List<FakeConnection> opened = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final ManualScheduler scheduler = new ManualScheduler();
    private ServiceInfo registryAnswer;
    private boolean refuseConnections;
    private Handshake handshake;
    private boolean dropDuringWarmup;
    private ServiceClient client;

    private final ServiceClientCallback callback = new ServiceClientCallback() {
        @Override
        public void onMessage(ServiceClient c, Message message) {
            events.add("message");
        }

        @Override
        public void onConnect(ServiceClient c, ServiceEndpoint endpoint) {
            events.add("connect " + endpoint.host());
        }

        @Override
        public void onDisconnect(ServiceClient c, ServiceEndpoint endpoint) {
            events.add("disconnect " + endpoint.host());
        }
    };

    @BeforeMethod
    public void setUp() {
        opened.clear();
        events.clear();
        scheduler.tasks.clear();
        refuseConnections = false;
        handshake = Handshake.COMPLETE;
        dropDuringWarmup = false;
        registryAnswer = new ServiceInfo(ORDERS, List.of(FIRST, SECOND));
        client = new ServiceClient("orders", id -> {
            if (registryAnswer == null) {
                throw new ServiceException("Unknown service " + id);
            }
            return registryAnswer;
        }, callback, this::open, scheduler);
    }

    @Test
    public void connectsToThePrimary() {
        client.connect();

        Assert.assertEquals(client.getEndpoint().orElseThrow(), FIRST);
        Assert.assertEquals(opened.size(), 1);
        Assert.assertEquals(events, List.of("connect 10.0.0.1"));
    }

    @Test
    public void spreadsLoadBalancedClientsAcrossInstances() {
        ServiceInfo info = new ServiceInfo(SEARCH, List.of(
                new ServiceEndpoint("10.0.0.1", 9002, 1, false), new ServiceEndpoint("10.0.0.2", 9002, 2, false)));
        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            chosen.add(ServiceClient.choose(info).host());
        }
        Assert.assertEquals(chosen, Set.of("10.0.0.1", "10.0.0.2"));
    }

    @Test
    public void failsWhenNoInstanceIsRunning() {
        registryAnswer = new ServiceInfo(ORDERS, List.of());
        ServiceException e = Assert.expectThrows(ServiceException.class, client::connect);
        Assert.assertTrue(e.getMessage().contains("no running instance"), e.getMessage());
        Assert.assertTrue(opened.isEmpty());
    }

    @Test
    public void failsForAnUnknownService() {
        registryAnswer = null;
        Assert.expectThrows(ServiceException.class, client::connect);
    }

    @Test
    public void failsWhenTheInstanceRefusesTheConnection() {
        refuseConnections = true;
        ServiceException e = Assert.expectThrows(ServiceException.class, client::connect);
        Assert.assertTrue(e.getMessage().contains("10.0.0.1:9001"), e.getMessage());
        Assert.assertTrue(opened.get(0).closed);
        Assert.assertFalse(client.isConnected());
    }

    @Test
    public void failsAtOnceWhenTheInstanceDropsTheHandshake() {
        handshake = Handshake.DROP;
        ServiceException e = Assert.expectThrows(ServiceException.class, client::connect);
        Assert.assertTrue(e.getMessage().contains("closed the connection"), e.getMessage());
        Assert.assertTrue(scheduler.tasks.isEmpty(), "a failed first connect does not retry");
    }

    @Test
    public void sendsOnlyWhileConnected() {
        Assert.expectThrows(ServiceException.class, () -> client.send(PING));
        client.connect();
        client.send(PING);
        Assert.assertEquals(opened.get(0).sent, List.of(PING));
    }

    @Test
    public void followsTheServiceToItsNewPrimary() {
        client.connect();
        FakeConnection lost = opened.get(0);

        registryAnswer = new ServiceInfo(ORDERS, List.of(new ServiceEndpoint("10.0.0.2", 9001, 1, true)));
        lost.drop();
        Assert.assertFalse(client.isConnected());
        assertExpectedThrowsOnSend();
        scheduler.runPending();

        Assert.assertEquals(client.getEndpoint().orElseThrow().host(), "10.0.0.2");
        Assert.assertEquals(events, List.of("connect 10.0.0.1", "disconnect 10.0.0.1", "connect 10.0.0.2"));
    }

    @Test
    public void keepsRetryingUntilAnInstanceIsBack() {
        client.connect();
        registryAnswer = new ServiceInfo(ORDERS, List.of());
        opened.get(0).drop();

        scheduler.runPending();
        scheduler.runPending();
        Assert.assertFalse(client.isConnected());
        Assert.assertEquals(scheduler.tasks.size(), 1, "still retrying");

        registryAnswer = new ServiceInfo(ORDERS, List.of(new ServiceEndpoint("10.0.0.2", 9001, 1, true)));
        scheduler.runPending();
        Assert.assertTrue(client.isConnected());
        Assert.assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void ignoresEventsFromAReplacedConnection() {
        client.connect();
        FakeConnection lost = opened.get(0);
        lost.drop();
        scheduler.runPending();
        events.clear();

        lost.listener.onMessage(PING);
        lost.listener.onDisconnect();

        Assert.assertTrue(events.isEmpty());
        Assert.assertTrue(client.isConnected());
    }

    @Test
    public void closingStopsReconnecting() {
        client.connect();
        client.close();
        Assert.assertTrue(opened.get(0).closed);

        opened.get(0).drop();
        scheduler.runPending();
        Assert.assertEquals(opened.size(), 1);
        Assert.assertTrue(client.isClosed());
    }

    @Test
    public void warmsUpBeforeReportingTheConnection() {
        client = warmingClient(5);
        client.connect();

        List<Message> warmups = opened.get(0).sent.stream().filter(ServiceWarmupMessage.class::isInstance).toList();
        Assert.assertEquals(warmups.size(), 5);
        Assert.assertEquals(events, List.of("connect 10.0.0.1"), "the warm-up answers never reach the application");
        client.send(PING);
        Assert.assertEquals(opened.get(0).sent.get(opened.get(0).sent.size() - 1), PING);
    }

    @Test
    public void warmsUpAgainAfterReconnecting() {
        client = warmingClient(3);
        client.connect();
        opened.get(0).drop();
        scheduler.runPending();

        Assert.assertEquals(opened.size(), 2);
        Assert.assertEquals(opened.get(1).sent.stream().filter(ServiceWarmupMessage.class::isInstance).count(), 3);
        Assert.assertTrue(client.isConnected());
    }

    @Test
    public void aConnectionLostDuringWarmupFailsTheConnect() {
        dropDuringWarmup = true;
        client = warmingClient(5);
        ServiceException e = Assert.expectThrows(ServiceException.class, client::connect);
        Assert.assertTrue(e.getMessage().contains("closed the connection"), e.getMessage());
        Assert.assertTrue(events.isEmpty(), "the application never saw a connection");
    }

    private ServiceClient warmingClient(int roundTrips) {
        return new ServiceClient("orders", id -> registryAnswer, callback, this::open, scheduler, roundTrips);
    }

    private void assertExpectedThrowsOnSend() {
        Assert.expectThrows(ServiceException.class, () -> client.send(PING));
    }

    private ServiceConnection open(ServiceEndpoint endpoint, ServiceConnection.Listener listener) {
        FakeConnection connection = new FakeConnection(listener);
        opened.add(connection);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable e) throws E {
        throw (E) e;
    }

    private enum Handshake { COMPLETE, DROP }

    private final class FakeConnection implements ServiceConnection {
        private final Listener listener;
        private final List<Message> sent = new ArrayList<>();
        private boolean connected;
        private boolean closed;

        FakeConnection(Listener listener) {
            this.listener = listener;
        }

        void drop() {
            connected = false;
            listener.onDisconnect();
        }

        @Override
        public void connect() {
            if (refuseConnections) {
                // As Netty does: a checked exception the signature does not declare
                throwUnchecked(new java.net.ConnectException("Connection refused"));
            }
            connected = true;
            if (handshake == Handshake.COMPLETE) {
                listener.onConnect();
            } else {
                drop();
            }
        }

        @Override
        public void send(Message message) {
            sent.add(message);
            // Stands in for a service running the SDK, which answers warm-up round trips
            if (message instanceof ServiceWarmupMessage warmup && !warmup.isReply()) {
                if (dropDuringWarmup && warmup.getSequence() == 2) {
                    drop();
                    return;
                }
                listener.onMessage(warmup.reply());
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }
    }

    /** Holds scheduled tasks until the test runs them, ignoring their delay. */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        ManualScheduler() {
            super(1);
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
