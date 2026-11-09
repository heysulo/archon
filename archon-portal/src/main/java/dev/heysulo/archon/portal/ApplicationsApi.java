package dev.heysulo.archon.portal;

import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.portal.applications.ApplicationRecord;
import dev.heysulo.archon.portal.applications.ApplicationStore;
import dev.heysulo.archon.portal.auth.AuthException;
import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
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
 */
final class ApplicationsApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationsApi.class);
    private static final String PREFIX = "/api/applications/";

    private final ApplicationStore store;
    private final ProcessFeed feed;
    private final Function<String, Optional<Principal>> sessions;

    ApplicationsApi(ApplicationStore store, ProcessFeed feed, Function<String, Optional<Principal>> sessions) {
        this.store = store;
        this.feed = feed;
        this.sessions = sessions;
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
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                Http.sendError(exchange, 404, "Not found");
                return;
            }
            String group = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String name = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            Http.requireSameOrigin(exchange);
            AuthService.require(user, Permission.APPLICATIONS_MANAGE);
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
