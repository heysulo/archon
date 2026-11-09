package dev.heysulo.archon.portal;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessLogBatch;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code GET /api/processes/{group}/{name}/{rank}/logs}: an application instance's log lines as they are written,
 * as server-sent events ({@code logs:view}). The instance sends lines only while someone watches, starting with the
 * ones it kept from before. Nothing is stored.
 *
 * <p>Each {@code lines} event holds {@code lines}, {@code dropped} (lines lost because they came too fast),
 * {@code history} and {@code status} (news such as the instance disconnecting).
 */
final class LogStreams {
    static final Pattern PATH = Pattern.compile("^/api/processes/([^/]+)/([^/]+)/(\\d+)/logs/?$");
    static final int MAX_STREAMS = 50;
    private static final long KEEPALIVE_SECONDS = 15;
    private static final Logger LOGGER = LoggerFactory.getLogger(LogStreams.class);

    private final ProcessFeed feed;
    private final Function<String, Optional<Principal>> sessions;
    private final Gson gson = new Gson();
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    // Writes to browsers block, so they never run on the thread that hears from the registry
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            new DefaultThreadFactory("portal-logs", true));
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("portal-logs-keepalive", true));

    LogStreams(ProcessFeed feed, Function<String, Optional<Principal>> sessions) {
        this.feed = feed;
        this.sessions = sessions;
        keepAlive.scheduleWithFixedDelay(this::keepAlive, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    static boolean matches(String path) {
        return PATH.matcher(path).matches();
    }

    void handle(HttpExchange exchange) throws IOException {
        Matcher matcher = PATH.matcher(exchange.getRequestURI().getRawPath());
        if (!matcher.matches()) {
            Http.sendError(exchange, 404, "Not found");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            Http.sendError(exchange, 405, "Method not allowed");
            return;
        }
        String token = Http.cookie(exchange, Http.SESSION_COOKIE).orElse("");
        Optional<Principal> user = sessions.apply(token);
        if (user.isEmpty()) {
            Http.sendError(exchange, 401, "Sign in first");
            return;
        }
        if (!user.get().can(Permission.LOGS_VIEW)) {
            Http.sendError(exchange, 403, "You do not have permission to watch logs");
            return;
        }
        if (streams.size() >= MAX_STREAMS) {
            Http.sendError(exchange, 503, "Too many open log streams");
            return;
        }
        String group = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
        String name = URLDecoder.decode(matcher.group(2), StandardCharsets.UTF_8);
        int rank = Integer.parseInt(matcher.group(3));
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", Http.NO_STORE);
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        Http.securityHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);
        Stream stream = new Stream(exchange, token);
        streams.add(stream);
        LOGGER.info("{} is watching the logs of {}:{} #{}", user.get().username(), group, name, rank);
        stream.write("retry: 3000\n\n");
        try {
            stream.unwatch = feed.watchLogs(group, name, rank,
                    batch -> writer.execute(() -> {
                        if (!stream.write("event: lines\ndata: " + gson.toJson(view(batch)) + "\n\n")) {
                            close(stream);
                        }
                    }));
        } catch (PortalException e) {
            stream.write("event: lines\ndata: " + gson.toJson(Map.of("lines", java.util.List.of(), "dropped", 0,
                    "history", false, "status", e.getMessage())) + "\n\n");
            close(stream);
        }
    }

    // Field names the frontend reads; null status left out
    private static Map<String, Object> view(ProcessLogBatch batch) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("lines", batch.getLines());
        view.put("dropped", batch.getDropped());
        view.put("history", batch.isHistory());
        if (batch.getStatus() != null) {
            view.put("status", batch.getStatus());
        }
        return view;
    }

    private void keepAlive() {
        streams.forEach(stream -> writer.execute(() -> {
            boolean allowed = sessions.apply(stream.sessionToken).filter(p -> p.can(Permission.LOGS_VIEW))
                    .isPresent();
            if (!allowed) {
                stream.write("event: signedout\ndata: {}\n\n");
                close(stream);
            } else if (!stream.write(": keepalive\n\n")) {
                close(stream);
            }
        }));
    }

    private void close(Stream stream) {
        if (streams.remove(stream)) {
            Runnable unwatch = stream.unwatch;
            if (unwatch != null) {
                unwatch.run();
            }
            stream.close();
        }
    }

    int openStreams() {
        return streams.size();
    }

    void stop() {
        streams.forEach(this::close);
        writer.shutdownNow();
        keepAlive.shutdownNow();
    }

    /** One browser's open log stream. */
    private static final class Stream {
        private final HttpExchange exchange;
        private final OutputStream out;
        private final String sessionToken;
        private volatile Runnable unwatch;

        Stream(HttpExchange exchange, String sessionToken) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
            this.sessionToken = sessionToken;
        }

        /** @return false once the browser has gone */
        synchronized boolean write(String event) {
            try {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            exchange.close();
        }
    }
}
