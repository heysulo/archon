package dev.heysulo.archon.portal;

import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.portal.applications.ApplicationRecord;
import dev.heysulo.archon.portal.applications.ApplicationStore;
import dev.heysulo.archon.portal.auth.AuthException;
import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
import dev.heysulo.archon.portal.tls.JoinTokenStore;
import dev.heysulo.archon.portal.tls.TlsStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/**
 * Application definitions, the applications whose instances may register:
 * <ul>
 *     <li>{@code GET /api/applications} every definition, Archon's own included ({@code applications:view})</li>
 *     <li>{@code POST /api/applications}, {@code PUT /api/applications/{group}/{name}} and
 *     {@code DELETE /api/applications/{group}/{name}} ({@code applications:manage})</li>
 * </ul>
 * A definition with a running instance cannot be deleted: its instances could not reconnect after a drop.
 *
 * <p>Join tokens, which let instances get their first certificate when TLS is on ({@code applications:manage}):
 * <ul>
 *     <li>{@code GET} and {@code POST /api/applications/{group}/{name}/tokens}</li>
 *     <li>{@code DELETE /api/applications/{group}/{name}/tokens/{id}}</li>
 * </ul>
 * And {@code GET /api/tls}, the CA's fingerprint applications join with ({@code applications:view}).
 */
final class ApplicationsApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationsApi.class);
    private static final String PREFIX = "/api/applications/";

    private static final String TOKENS = "tokens";
    private static final long HOURS_PER_YEAR = 24L * 365;

    private final ApplicationStore store;
    private final ProcessFeed feed;
    private final JoinTokenStore tokens;
    private final TlsStatus tls;
    private final Function<String, Optional<Principal>> sessions;

    ApplicationsApi(ApplicationStore store, ProcessFeed feed, JoinTokenStore tokens, TlsStatus tls,
            Function<String, Optional<Principal>> sessions) {
        this.store = store;
        this.feed = feed;
        this.tokens = tokens;
        this.tls = tls;
        this.sessions = sessions;
    }

    // Filled by Gson
    static final class TokenBody {
        String description;
        boolean reusable;
        // Null for a token that never expires
        Long expiresInHours;
    }

    void handleTls(HttpExchange exchange) throws IOException {
        try {
            Principal user = Http.cookie(exchange, Http.SESSION_COOKIE).flatMap(sessions)
                    .orElseThrow(() -> new AuthException(401, "Sign in first"));
            if (!exchange.getRequestMethod().equals("GET")) {
                Http.sendError(exchange, 405, "Method not allowed");
                return;
            }
            AuthService.require(user, Permission.APPLICATIONS_VIEW);
            Http.sendJson(exchange, 200, tls.view());
        } catch (AuthException e) {
            Http.sendError(exchange, e.status(), e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Failed to read the TLS status", e);
            Http.sendError(exchange, 500, "Something went wrong");
        }
    }

    private void handleTokens(HttpExchange exchange, Principal user, String group, String name, String[] parts)
            throws IOException {
        if (ApplicationStore.ARCHON_GROUP.equals(group)) {
            throw new AuthException(400, "Archon's own components join by themselves");
        }
        if (!store.isDefined(group, name)) {
            throw new AuthException(404, group + ":" + name + " is not defined");
        }
        String method = exchange.getRequestMethod();
        if (parts.length == 3 && method.equals("GET")) {
            Http.sendJson(exchange, 200, tokens.list(group, name));
        } else if (parts.length == 3 && method.equals("POST")) {
            TokenBody body = Http.readJson(exchange, TokenBody.class);
            if (body.expiresInHours != null && (body.expiresInHours < 1 || body.expiresInHours > HOURS_PER_YEAR)) {
                throw new AuthException(400, "A token lasts from an hour up to a year, or never expires");
            }
            JoinTokenStore.Created created = tokens.create(group, name, body.description, body.reusable,
                    body.expiresInHours == null ? null : java.time.Duration.ofHours(body.expiresInHours),
                    user.username());
            LOGGER.info("{} created {} join token {} for {}:{}", user.username(),
                    body.reusable ? "a reusable" : "a single-use", created.record().id(), group, name);
            Http.sendJson(exchange, 201, created);
        } else if (parts.length == 4 && method.equals("DELETE")) {
            long id;
            try {
                id = Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                throw new AuthException(404, "No such join token");
            }
            tokens.delete(group, name, id);
            LOGGER.info("{} revoked join token {} of {}:{}", user.username(), id, group, name);
            Http.sendNoContent(exchange);
        } else {
            Http.sendError(exchange, 405, "Method not allowed");
        }
    }

    // Filled by Gson
    static final class ApplicationBody {
        String group;
        String name;
        String description;
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            Principal user = Http.cookie(exchange, Http.SESSION_COOKIE).flatMap(sessions)
                    .orElseThrow(() -> new AuthException(401, "Sign in first"));
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/applications") || path.equals(PREFIX)) {
                if (method.equals("GET")) {
                    AuthService.require(user, Permission.APPLICATIONS_VIEW);
                    Http.sendJson(exchange, 200, store.list());
                } else if (method.equals("POST")) {
                    Http.requireSameOrigin(exchange);
                    AuthService.require(user, Permission.APPLICATIONS_MANAGE);
                    ApplicationBody body = Http.readJson(exchange, ApplicationBody.class);
                    ApplicationRecord created = store.create(body.group, body.name, body.description);
                    LOGGER.info("{} defined {}:{}", user.username(), created.group(), created.name());
                    Http.sendJson(exchange, 201, created);
                } else {
                    Http.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            String[] parts = path.startsWith(PREFIX) ? path.substring(PREFIX.length()).split("/", -1) : new String[0];
            boolean tokenPath = (parts.length == 3 || parts.length == 4) && TOKENS.equals(parts[2]);
            if ((parts.length != 2 && !tokenPath) || parts[0].isEmpty() || parts[1].isEmpty()) {
                Http.sendError(exchange, 404, "Not found");
                return;
            }
            String group = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String name = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (!method.equals("GET")) {
                Http.requireSameOrigin(exchange);
            }
            AuthService.require(user, Permission.APPLICATIONS_MANAGE);
            if (tokenPath) {
                handleTokens(exchange, user, group, name, parts);
                return;
            }
            if (method.equals("PUT")) {
                ApplicationBody body = Http.readJson(exchange, ApplicationBody.class);
                Http.sendJson(exchange, 200, store.update(group, name, body.description));
            } else if (method.equals("DELETE")) {
                requireStopped(group, name);
                store.delete(group, name);
                LOGGER.info("{} removed the definition of {}:{}", user.username(), group, name);
                Http.sendNoContent(exchange);
            } else {
                Http.sendError(exchange, 405, "Method not allowed");
            }
        } catch (AuthException e) {
            Http.sendError(exchange, e.status(), e.getMessage());
        } catch (IllegalStateException e) {
            LOGGER.warn("Application definitions unavailable: {}", e.getMessage());
            Http.sendError(exchange, 503, "Cannot reach the database right now");
        } catch (RuntimeException e) {
            LOGGER.error("Failed {} {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            Http.sendError(exchange, 500, "Something went wrong");
        }
    }

    // Running instances would be refused on their next reconnect. Unknown while no registry answers, so refused then.
    private void requireStopped(String group, String name) {
        long running;
        try {
            running = feed.current().processes().stream()
                    .filter(process -> process.group().equals(group) && process.name().equals(name)
                            && process.running())
                    .count();
        } catch (PortalException e) {
            throw new AuthException(503, "Cannot tell whether " + group + ":" + name + " is running: "
                    + e.getMessage());
        }
        if (running > 0) {
            throw new AuthException(409, group + ":" + name + " has " + running + (running == 1 ? " instance" :
                    " instances") + " running. Stop them before removing its definition");
        }
    }
}
