package dev.heysulo.archon.portal;

import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.dictionary.sdk.services.ProcessInfo;
import dev.heysulo.archon.portal.auth.AuthException;
import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
import dev.heysulo.archon.portal.services.ServiceRecord;
import dev.heysulo.archon.portal.services.ServiceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Service definitions:
 * <ul>
 *     <li>{@code GET /api/services} every service ({@code services:view})</li>
 *     <li>{@code POST /api/services}, {@code PUT /api/services/{id}}, {@code DELETE /api/services/{id}}
 *     ({@code services:manage})</li>
 * </ul>
 * Running instances are not here: the process stream already lists what each instance serves. A service that any
 * instance is serving cannot be changed or deleted: its instances would keep the old port or owner, and clients
 * would be sent somewhere that no longer matches the definition. Stop it first.
 */
final class ServicesApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServicesApi.class);
    private static final String PREFIX = "/api/services/";

    private final ServiceStore store;
    private final ProcessFeed feed;
    private final Function<String, Optional<Principal>> sessions;

    /** @param feed the registry's process list, which says what each instance serves */
    ServicesApi(ServiceStore store, ProcessFeed feed, Function<String, Optional<Principal>> sessions) {
        this.store = store;
        this.feed = feed;
        this.sessions = sessions;
    }

    // Filled by Gson
    static final class ServiceBody {
        String id;
        String name;
        Integer portOffset;
        String group;
        String application;
        String mode;

        ServiceRecord toRecord() {
            if (portOffset == null) {
                throw new AuthException(400, "Give the service a port offset");
            }
            return new ServiceRecord(id, name, portOffset, group, application, mode, false, null, null);
        }
    }

    /**
     * Refuses while any instance serves the service. When no registry answers, whether it runs is unknown, so the
     * change is refused too.
     */
    private void requireStopped(String id) {
        List<String> serving;
        try {
            serving = servedBy(feed.current().processes(), id);
        } catch (PortalException e) {
            throw new AuthException(503, "Cannot tell whether " + id + " is running: " + e.getMessage());
        }
        if (!serving.isEmpty()) {
            throw new AuthException(409, id + " is running on " + String.join(", ", serving)
                    + ". Stop it before changing it");
        }
    }

    /** The instances that list the service among those they serve, as {@code Group / Name #rank}. */
    static List<String> servedBy(List<ProcessInfo> processes, String id) {
        String prefix = id + "@";
        return processes.stream()
                .filter(process -> process.services() != null
                        && process.services().stream().anyMatch(served -> served.startsWith(prefix)))
                .sorted(java.util.Comparator.comparing(ProcessInfo::group).thenComparing(ProcessInfo::name)
                        .thenComparingInt(ProcessInfo::rank))
                .map(process -> process.group() + " / " + process.name() + " #" + process.rank())
                .toList();
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            Principal user = Http.cookie(exchange, Http.SESSION_COOKIE).flatMap(sessions)
                    .orElseThrow(() -> new AuthException(401, "Sign in first"));
            // Raw, so an id with an encoded slash is still one id
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/services") || path.equals(PREFIX)) {
                if (method.equals("GET")) {
                    AuthService.require(user, Permission.SERVICES_VIEW);
                    Http.sendJson(exchange, 200, store.list());
                } else if (method.equals("POST")) {
                    Http.requireSameOrigin(exchange);
                    AuthService.require(user, Permission.SERVICES_MANAGE);
                    ServiceRecord created = store.create(Http.readJson(exchange, ServiceBody.class).toRecord());
                    LOGGER.info("{} added service {}", user.username(), created.id());
                    Http.sendJson(exchange, 201, created);
                } else {
                    Http.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            if (!path.startsWith(PREFIX) || path.indexOf('/', PREFIX.length()) >= 0) {
                Http.sendError(exchange, 404, "Not found");
                return;
            }
            String id = URLDecoder.decode(path.substring(PREFIX.length()), StandardCharsets.UTF_8);
            Http.requireSameOrigin(exchange);
            AuthService.require(user, Permission.SERVICES_MANAGE);
            if (method.equals("PUT") || method.equals("DELETE")) {
                requireStopped(id);
            }
            if (method.equals("PUT")) {
                ServiceRecord updated = store.update(id, Http.readJson(exchange, ServiceBody.class).toRecord());
                LOGGER.info("{} changed service {}", user.username(), id);
                Http.sendJson(exchange, 200, updated);
            } else if (method.equals("DELETE")) {
                store.delete(id);
                LOGGER.info("{} deleted service {}", user.username(), id);
                Http.sendNoContent(exchange);
            } else {
                Http.sendError(exchange, 405, "Method not allowed");
            }
        } catch (AuthException e) {
            Http.sendError(exchange, e.status(), e.getMessage());
        } catch (IllegalStateException e) {
            LOGGER.warn("Services unavailable: {}", e.getMessage());
            Http.sendError(exchange, 503, "Cannot reach the database right now");
        } catch (RuntimeException e) {
            LOGGER.error("Failed {} {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            Http.sendError(exchange, 500, "Something went wrong");
        }
    }
}
