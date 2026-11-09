package dev.heysulo.archon.portal;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
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
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Serves the portal frontend and its API:
 * <ul>
 *     <li>{@code GET /}, and any other path that is not a file or the API, the frontend app, which routes itself</li>
 *     <li>{@code GET /assets/...} the app's files, cached for good because their names change with their content</li>
 *     <li>{@code GET /api/events} a Server-Sent Events stream: the process list at once, then again on every change,
 *     or an {@code offline} event while no primary registry answers</li>
 *     <li>{@code GET /api/processes} the latest process list as JSON, or 503 with an error</li>
 *     <li>{@code /api/auth/...}, {@code /api/users...} and {@code /api/roles}, see {@link AuthApi}</li>
 *     <li>{@code /api/configs...}, see {@link ConfigApi}, and {@code /api/services...}, see {@link ServicesApi}</li>
 *     <li>{@code GET /healthz} 200 while the portal is up, whatever the registries are doing</li>
 * </ul>
 * The frontend's files are public; every API that returns data needs a signed-in user with the right permission.
 */
final class PortalServer implements ProcessFeed.Listener {
    static final int MAX_STREAMS = 200;
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalServer.class);
    private static final int THREADS = 8;
    // A comment line now and then keeps proxies and load balancers from closing an idle stream. Each stream's
    // session is checked again at the same time, so signing out or being disabled ends it.
    private static final long KEEPALIVE_SECONDS = 15;
    // Tells the browser how soon to reconnect, possibly to another replica, after a stream breaks
    private static final String RETRY = "retry: 1000\n\n";
    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private final Gson gson = new Gson();
    private final ProcessFeed feed;
    private final UnaryOperator<ProcessSnapshot> stamp;
    private final StaticAssets assets;
    private final AuthApi authApi;
    private final ConfigApi configApi;
    private final ServicesApi servicesApi;
    private final ApplicationsApi applicationsApi;
    private final Function<String, Optional<Principal>> sessions;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    // Writes to browsers block, so they never run on the thread that hears from the registry
    private final ScheduledExecutorService broadcaster = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("portal-broadcast", true));
    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param stamp     adds this replica's identity to each snapshot before it is sent
     * @param assetRoot the classpath directory holding the built frontend
     * @param sessions  finds who a session token belongs to
     */
    PortalServer(ProcessFeed feed, UnaryOperator<ProcessSnapshot> stamp, String assetRoot, AuthApi authApi,
            ConfigApi configApi, ServicesApi servicesApi, ApplicationsApi applicationsApi,
            Function<String, Optional<Principal>> sessions) {
        this.applicationsApi = applicationsApi;
        this.configApi = configApi;
        this.servicesApi = servicesApi;
        this.feed = feed;
        this.stamp = stamp;
        this.assets = new StaticAssets(assetRoot);
        this.authApi = authApi;
        this.sessions = sessions;
        if (assets.index().isEmpty()) {
            LOGGER.warn("No frontend at {} in this build; the API still works", assetRoot);
        }
    }

    void start(int port) throws IOException {
        start(port, java.util.Optional.empty());
    }

    /** @param https serves HTTPS with this context, or plain HTTP when empty */
    void start(int port, java.util.Optional<javax.net.ssl.SSLContext> https) throws IOException {
        if (https.isPresent()) {
            com.sun.net.httpserver.HttpsServer secure = com.sun.net.httpserver.HttpsServer.create(
                    new InetSocketAddress(port), 0);
            secure.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(https.get()));
            server = secure;
        } else {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        }
        server.createContext("/api/events", this::events);
        server.createContext("/api/processes", this::processes);
        server.createContext("/api/auth/", authApi::handleAuth);
        server.createContext("/api/users", authApi::handleUsers);
        server.createContext("/api/roles", authApi::handleRoles);
        server.createContext("/api/configs", configApi::handle);
        server.createContext("/api/services", servicesApi::handle);
        server.createContext("/api/applications", applicationsApi::handle);
        server.createContext("/api/tls", applicationsApi::handleTls);
        server.createContext("/healthz", exchange -> Http.sendText(exchange, 200, "ok"));
        server.createContext("/", this::frontend);
        executor = Executors.newFixedThreadPool(THREADS);
        server.setExecutor(executor);
        feed.subscribe(this);
        broadcaster.scheduleWithFixedDelay(this::keepAlive, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        server.start();
        LOGGER.info("Portal listening on port {} over {}", port, https.isPresent() ? "HTTPS" : "HTTP");
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

    /**
     * The signed-in user allowed to see processes, or empty after answering 401 or 403.
     */
    private Optional<Principal> processViewer(HttpExchange exchange) throws IOException {
        Optional<Principal> principal = Http.cookie(exchange, Http.SESSION_COOKIE).flatMap(sessions);
        if (principal.isEmpty()) {
            Http.sendError(exchange, 401, "Sign in first");
            return Optional.empty();
        }
        if (!principal.get().can(Permission.PROCESSES_VIEW)) {
            Http.sendError(exchange, 403, "You do not have permission to see running processes");
            return Optional.empty();
        }
        return principal;
    }

    private void events(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            return;
        }
        if (processViewer(exchange).isEmpty()) {
            return;
        }
        if (streams.size() >= MAX_STREAMS) {
            Http.sendError(exchange, 503, "Too many open streams");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", Http.NO_STORE);
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        Http.securityHeaders(exchange);
        // Length 0 streams the response in chunks until it is closed
        exchange.sendResponseHeaders(200, 0);
        Stream stream = new Stream(exchange, Http.cookie(exchange, Http.SESSION_COOKIE).orElse(""));
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
        if (!isGet(exchange) || processViewer(exchange).isEmpty()) {
            return;
        }
        try {
            Http.sendJson(exchange, 200, stamp.apply(feed.current()));
        } catch (PortalException e) {
            Http.sendError(exchange, 503, e.getMessage());
        }
    }

    private void frontend(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/")) {
            Http.sendError(exchange, 404, "Not found");
            return;
        }
        Optional<StaticAssets.Asset> file = path.equals("/") ? Optional.empty() : assets.find(path);
        if (file.isPresent()) {
            String caching = path.startsWith("/assets/") ? CACHE_FOREVER : "no-cache";
            Http.send(exchange, 200, file.get().contentType(), file.get().body(), caching);
            return;
        }
        if (StaticAssets.looksLikeFile(path)) {
            Http.sendText(exchange, 404, "Not found");
            return;
        }
        // An app route such as /processes: the app reads the address and shows the page itself. Never cached, so a
        // deploy reaches the browser on its next load.
        Optional<StaticAssets.Asset> index = assets.index();
        if (index.isEmpty()) {
            Http.sendText(exchange, 503, "The frontend is not part of this build");
            return;
        }
        Http.send(exchange, 200, index.get().contentType(), index.get().body(), Http.NO_STORE);
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

    // Runs on the broadcaster only
    private void keepAlive() {
        streams.removeIf(stream -> {
            if (sessions.apply(stream.sessionToken).filter(p -> p.can(Permission.PROCESSES_VIEW)).isEmpty()) {
                stream.write("event: signedout\ndata: {}\n\n");
                stream.close();
                return true;
            }
            return !stream.write(": keepalive\n\n");
        });
    }

    private boolean isGet(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "GET");
        Http.sendError(exchange, 405, "Method not allowed");
        return false;
    }

    /** One browser's open event stream, and the session that opened it. */
    private static final class Stream {
        private final HttpExchange exchange;
        private final OutputStream out;
        private final String sessionToken;

        Stream(HttpExchange exchange, String sessionToken) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
            this.sessionToken = sessionToken;
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
