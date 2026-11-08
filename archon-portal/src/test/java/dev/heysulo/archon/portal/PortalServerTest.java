package dev.heysulo.archon.portal;

import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;
import dev.heysulo.archon.dictionary.sdk.services.ProcessInfo;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class PortalServerTest {

    private static final ProcessInfo LEADER =
            new ProcessInfo("Group", "Application", 1, "RUNNING", true, true, List.of("ping@10.0.0.5:9001"),
                    new InstanceDetails("app-7d9f", "10.0.0.5", "node-a"));
    private static final ProcessInfo STOPPED =
            new ProcessInfo("Group", "Application", 2, "KILLED", false, false, List.of(), null);

    private final HttpClient http = HttpClient.newHttpClient();
    private final PortalIdentity identity = new PortalIdentity();
    private final FakeFeed feed = new FakeFeed();
    private PortalServer server;
    private int port;

    @BeforeClass
    public void start() throws IOException {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        server = new PortalServer(feed, snapshot -> snapshot.servedBy(identity), "/portal-test");
        server.start(port);
    }

    @AfterClass
    public void stop() {
        server.stop();
    }

    @BeforeMethod
    public void registryUp() {
        feed.snapshot = ProcessSnapshot.of("10.21.3.202", 3, List.of(LEADER, STOPPED));
    }

    @Test
    public void servesTheLatestProcessesAsJson() throws Exception {
        HttpResponse<String> response = get("/api/processes");
        Assert.assertEquals(response.statusCode(), 200);
        Assert.assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        Assert.assertTrue(response.body().contains("\"registry\":\"10.21.3.202\""), response.body());
        Assert.assertTrue(response.body().contains("\"runLevel\":\"KILLED\""), response.body());
        Assert.assertTrue(response.body().contains("ping@10.0.0.5:9001"), response.body());
        Assert.assertTrue(response.body().contains("\"servedBy\":{\"host\""), response.body());
        Assert.assertTrue(response.body().contains("\"details\":{\"host\":\"app-7d9f\",\"address\":\"10.0.0.5\","
                + "\"node\":\"node-a\"}"), response.body());
    }

    @Test
    public void reportsAnUnreachableRegistryAs503() throws Exception {
        feed.snapshot = null;
        HttpResponse<String> response = get("/api/processes");
        Assert.assertEquals(response.statusCode(), 503);
        Assert.assertTrue(response.body().contains("No primary registry is answering"), response.body());
    }

    // The page subscribes once; every change then arrives without asking
    @Test
    public void streamsTheListAtOnceThenEveryChange() throws Exception {
        try (EventStream events = openEvents()) {
            Assert.assertEquals(events.nextEvent(), "snapshot");
            Assert.assertTrue(events.lastData.contains("10.21.3.202"), events.lastData);

            ProcessInfo promoted = new ProcessInfo("Group", "Application", 1, "RUNNING", true, true, List.of(), null);
            feed.push(ProcessSnapshot.of("10.21.3.203", 4, List.of(promoted)));

            Assert.assertEquals(events.nextEvent(), "snapshot");
            Assert.assertTrue(events.lastData.contains("10.21.3.203"), events.lastData);
            Assert.assertTrue(events.lastData.contains("\"term\":4"), events.lastData);
        }
    }

    @Test
    public void streamsAnOfflineEventWhenTheRegistryIsLost() throws Exception {
        try (EventStream events = openEvents()) {
            Assert.assertEquals(events.nextEvent(), "snapshot");
            feed.lose("Lost the registry at 10.21.3.202");
            Assert.assertEquals(events.nextEvent(), "offline");
            Assert.assertTrue(events.lastData.contains("Lost the registry"), events.lastData);
        }
    }

    @Test
    public void startsAStreamOfflineWhileNoRegistryAnswers() throws Exception {
        feed.snapshot = null;
        try (EventStream events = openEvents()) {
            Assert.assertEquals(events.nextEvent(), "offline");
        }
    }

    @Test
    public void servesTheAppForEveryRoute() throws Exception {
        for (String route : List.of("/", "/processes", "/processes/Group/Application")) {
            HttpResponse<String> response = get(route);
            Assert.assertEquals(response.statusCode(), 200, route);
            Assert.assertTrue(response.body().contains("<title>Archon Portal</title>"), route);
            Assert.assertEquals(response.headers().firstValue("Cache-Control").orElse(""), "no-store", route);
        }
        Assert.assertEquals(get("/healthz").statusCode(), 200);
    }

    // File names carry a content hash, so a file under /assets/ never changes and can be cached for good
    @Test
    public void servesAssetsWithLongCaching() throws Exception {
        HttpResponse<String> script = get("/assets/app-1a2b3c.js");
        Assert.assertEquals(script.statusCode(), 200);
        Assert.assertTrue(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        Assert.assertTrue(script.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
        Assert.assertEquals(get("/favicon.svg").headers().firstValue("Content-Type").orElse(""), "image/svg+xml");
    }

    @Test
    public void answersMissingFilesAndApiPathsWith404() throws Exception {
        Assert.assertEquals(get("/assets/gone-9f8e7d.js").statusCode(), 404);
        Assert.assertEquals(get("/robots.txt").statusCode(), 404);
        Assert.assertEquals(get("/api/nothing").statusCode(), 404);
    }

    @Test
    public void refusesPathsLeavingTheBuildDirectory() throws Exception {
        // Encoded dots reach the server as "..", which must never resolve outside the frontend's files
        HttpResponse<String> response = get("/assets/%2e%2e/%2e%2e/dev/heysulo/archon/portal/PortalServer.class");
        Assert.assertEquals(response.statusCode(), 404);
        Assert.assertFalse(response.body().contains("PortalServer"));
    }

    @Test
    public void setsSecurityHeaders() throws Exception {
        HttpResponse<String> response = get("/");
        Assert.assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains(
                "default-src 'self'"));
        Assert.assertEquals(response.headers().firstValue("X-Frame-Options").orElse(""), "DENY");
        Assert.assertEquals(response.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff");
    }

    @Test
    public void refusesWrites() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/processes"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        Assert.assertEquals(http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode(), 405);
    }

    @Test
    public void namesPortalRolesByRank() {
        Assert.assertEquals(PortalIdentity.role(1), "primary");
        Assert.assertEquals(PortalIdentity.role(4), "mirror");
        Assert.assertEquals(PortalIdentity.role(-1), "unregistered");
    }

    @Test
    public void rejectsAnInvalidPort() {
        Assert.assertEquals(PortalApplication.port(null), PortalApplication.DEFAULT_PORT);
        Assert.assertEquals(PortalApplication.port("9090"), 9090);
        Assert.expectThrows(IllegalArgumentException.class, () -> PortalApplication.port("http"));
        Assert.expectThrows(IllegalArgumentException.class, () -> PortalApplication.port("70000"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private EventStream openEvents() throws Exception {
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Assert.assertEquals(response.statusCode(), 200);
        Assert.assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        EventStream stream = new EventStream(response.body());
        // The server registers the stream once its first event is written
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        stream.peek();
        while (server.openStreams() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return stream;
    }

    /** Reads Server-Sent Events: blocks of "event:" and "data:" lines separated by blank lines. */
    private static final class EventStream implements AutoCloseable {
        private final InputStream body;
        private final BufferedReader reader;
        private final List<String> buffered = new ArrayList<>();
        private String lastData;

        EventStream(InputStream body) {
            this.body = body;
            this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        void peek() throws IOException {
            String line = reader.readLine();
            if (line != null) {
                buffered.add(line);
            }
        }

        String nextEvent() throws IOException {
            String event = null;
            while (true) {
                String line = buffered.isEmpty() ? reader.readLine() : buffered.remove(0);
                Assert.assertNotNull(line, "stream ended");
                if (line.startsWith("event: ")) {
                    event = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    lastData = line.substring(6);
                } else if (line.isEmpty() && event != null) {
                    return event;
                }
            }
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static final class FakeFeed implements ProcessFeed {
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private volatile ProcessSnapshot snapshot;

        @Override
        public ProcessSnapshot current() {
            ProcessSnapshot current = snapshot;
            if (current == null) {
                throw new PortalException("No primary registry is answering");
            }
            return current;
        }

        @Override
        public void subscribe(Listener listener) {
            listeners.add(listener);
        }

        void push(ProcessSnapshot next) {
            snapshot = next;
            listeners.forEach(listener -> listener.onSnapshot(next));
        }

        void lose(String reason) {
            snapshot = null;
            listeners.forEach(listener -> listener.onLost(reason));
        }
    }
}
