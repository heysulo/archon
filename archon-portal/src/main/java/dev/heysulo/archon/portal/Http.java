package dev.heysulo.archon.portal;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.portal.auth.AuthException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** What every portal handler shares: responses, JSON, cookies and the checks on requests that change things. */
final class Http {
    static final String SESSION_COOKIE = "archon_session";
    /** Sent by the frontend on every request that changes something. Pages on other sites cannot set it. */
    static final String REQUEST_HEADER = "X-Archon-Request";
    static final String NO_STORE = "no-store";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    // Same origin only; Mantine sets inline styles, so styles may be inline
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    private static final Gson GSON = new Gson();

    private Http() {
    }

    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json", GSON.toJson(body).getBytes(StandardCharsets.UTF_8), NO_STORE);
    }

    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), NO_STORE);
    }

    static void sendNoContent(HttpExchange exchange) throws IOException {
        securityHeaders(exchange);
        exchange.getResponseHeaders().set("Cache-Control", NO_STORE);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] body, String caching)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", caching);
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }

    /**
     * Reads a JSON body into the given type.
     *
     * @throws AuthException with status 400 if the body is too large or not the expected JSON
     */
    static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new AuthException(415, "Send JSON");
        }
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new AuthException(413, "The request is too large");
        }
        try {
            T parsed = GSON.fromJson(new String(body, StandardCharsets.UTF_8), type);
            if (parsed == null) {
                throw new AuthException(400, "Send a JSON object");
            }
            return parsed;
        } catch (JsonParseException e) {
            throw new AuthException(400, "The request is not valid JSON");
        }
    }

    /**
     * Refuses requests that change something unless they come from the portal's own pages. The session cookie is
     * SameSite=Strict as well; this is a second, independent guard against requests forged by other sites.
     */
    static void requireSameOrigin(HttpExchange exchange) {
        if (exchange.getRequestHeaders().getFirst(REQUEST_HEADER) == null) {
            throw new AuthException(403, "Missing " + REQUEST_HEADER + " header");
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (origin != null && host != null) {
            String originHost;
            try {
                URI uri = URI.create(origin);
                originHost = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            } catch (IllegalArgumentException e) {
                originHost = null;
            }
            if (originHost == null || !originHost.equalsIgnoreCase(host)) {
                throw new AuthException(403, "Cross-site requests are not allowed");
            }
        }
    }

    static Optional<String> cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name) && !pair[1].isBlank()) {
                    return Optional.of(pair[1]);
                }
            }
        }
        return Optional.empty();
    }

    /** Sets the session cookie: unreadable by scripts, never sent by other sites, Secure when served over HTTPS. */
    static void setSessionCookie(HttpExchange exchange, String token, Duration maxAge) {
        StringBuilder cookie = new StringBuilder(SESSION_COOKIE).append('=').append(token)
                .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(maxAge.toSeconds());
        if (isHttps(exchange)) {
            cookie.append("; Secure");
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    static void clearSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    // Behind a TLS-terminating proxy, the proxy says so
    private static boolean isHttps(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwarded) || exchange instanceof com.sun.net.httpserver.HttpsExchange;
    }
}
