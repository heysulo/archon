package dev.heysulo.archon.portal;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Serves the portal page and its API:
 * <ul>
 *     <li>{@code GET /}, and any other path that is not a file or the API, the frontend app, which routes itself</li>
 *     <li>{@code GET /assets/...} the app's files, cached for good because their names change with their content</li>
 *     <li>{@code GET /api/events} a Server-Sent Events stream: the process list at once, then again on every change,
 *     or an {@code offline} event while no primary registry answers</li>
 *     <li>{@code GET /api/processes} the latest process list as JSON, or 503 with an error</li>
 *     <li>{@code GET /healthz} 200 while the portal is up, whatever the registries are doing</li>
 * </ul>
 */
final class PortalServer implements ProcessFeed.Listener {
    static final int MAX_STREAMS = 200;
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalServer.class);
    // Same origin only; Mantine sets inline styles, so styles may be inline
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";
    private static final String NO_STORE = "no-store";
    private static final int THREADS = 4;
    // A comment line now and then keeps proxies and load balancers from closing an idle stream
    private static final long KEEPALIVE_SECONDS = 15;
    // Tells the browser how soon to reconnect, possibly to another replica, after a stream breaks
    private static final String RETRY = "retry: 1000\n\n";

    private final Gson gson = new Gson();
    private final ProcessFeed feed;
    private final UnaryOperator<ProcessSnapshot> stamp;
    private final StaticAssets assets;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    // Writes to browsers block, so they never run on the thread that hears from the registry
    private final ScheduledExecutorService broadcaster = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("portal-broadcast", true));
    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param stamp     adds this replica's identity to each snapshot before it is sent
     * @param assetRoot the classpath directory holding the built frontend
     */
    PortalServer(ProcessFeed feed, UnaryOperator<ProcessSnapshot> stamp, String assetRoot) {
        this.feed = feed;
        this.stamp = stamp;
        this.assets = new StaticAssets(assetRoot);
        if (assets.index().isEmpty()) {
            LOGGER.warn("No frontend at {} in this build; the API still works", assetRoot);
        }
    }

    void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/events", this::events);
        server.createContext("/api/processes", this::processes);
        server.createContext("/healthz", exchange -> send(exchange, 200, "text/plain", "ok"));
        server.createContext("/", this::frontend);
        executor = Executors.newFixedThreadPool(THREADS);
        server.setExecutor(executor);
        feed.subscribe(this);
        broadcaster.scheduleWithFixedDelay(() -> broadcast(": keepalive\n\n"), KEEPALIVE_SECONDS, KEEPALIVE_SECONDS,
                TimeUnit.SECONDS);
        server.start();
        LOGGER.info("Portal listening on port {}", port);
    }

    void stop() {
        streams.forEach(Stream::close);
        server.stop(0);
        executor.shutdownNow();
        broadcaster.shutdownNow();
    }

    int openStreams() {
        return streams.size();
    }

    @Override
    public void onSnapshot(ProcessSnapshot snapshot) {
        broadcaster.execute(() -> broadcast(snapshotEvent(snapshot)));
    }

    @Override
    public void onLost(String reason) {
        broadcaster.execute(() -> broadcast(offlineEvent(reason)));
    }

    private void events(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            return;
        }
        if (streams.size() >= MAX_STREAMS) {
            send(exchange, 503, "text/plain", "Too many open streams");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        securityHeaders(exchange);
        // Length 0 streams the response in chunks until it is closed
        exchange.sendResponseHeaders(200, 0);
        Stream stream = new Stream(exchange);
        String first;
        try {
            first = snapshotEvent(feed.current());
        } catch (PortalException e) {
            first = offlineEvent(e.getMessage());
        }
        // Queued behind any broadcast in progress, so the stream starts with the newest state
        String opening = RETRY + first;
        broadcaster.execute(() -> {
            if (stream.write(opening)) {
                streams.add(stream);
            }
        });
        // The handler returns, but the exchange stays open: the broadcaster writes to it from now on
    }

    private void processes(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            return;
        }
        try {
            send(exchange, 200, "application/json", gson.toJson(stamp.apply(feed.current())));
        } catch (PortalException e) {
            send(exchange, 503, "application/json", gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    private void frontend(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/")) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        Optional<StaticAssets.Asset> file = path.equals("/") ? Optional.empty() : assets.find(path);
        if (file.isPresent()) {
            String caching = path.startsWith("/assets/") ? CACHE_FOREVER : "no-cache";
            send(exchange, 200, file.get().contentType(), file.get().body(), caching);
            return;
        }
        if (StaticAssets.looksLikeFile(path)) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        // An app route such as /processes: the app reads the address and shows the page itself. Never cached, so a
        // deploy reaches the browser on its next load.
        Optional<StaticAssets.Asset> index = assets.index();
        if (index.isEmpty()) {
            send(exchange, 503, "text/plain", "The frontend is not part of this build");
            return;
        }
        send(exchange, 200, index.get().contentType(), index.get().body(), NO_STORE);
    }

    private String snapshotEvent(ProcessSnapshot snapshot) {
        return "event: snapshot\ndata: " + gson.toJson(stamp.apply(snapshot)) + "\n\n";
    }

    private String offlineEvent(String reason) {
        return "event: offline\ndata: " + gson.toJson(Map.of("error", reason)) + "\n\n";
    }

    // Runs on the broadcaster only
    private void broadcast(String event) {
        streams.removeIf(stream -> !stream.write(event));
    }

    private boolean isGet(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "GET");
        send(exchange, 405, "text/plain", "Method not allowed");
        return false;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8), NO_STORE);
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body, String caching)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", caching);
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }

    /** One browser's open event stream. */
    private static final class Stream {
        private final HttpExchange exchange;
        private final OutputStream out;

        Stream(HttpExchange exchange) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        /** @return false once the browser has gone, after which the stream is closed */
        boolean write(String event) {
            try {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        void close() {
            exchange.close();
        }
    }
}
