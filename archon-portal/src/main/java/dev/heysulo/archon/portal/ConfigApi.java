package dev.heysulo.archon.portal;

import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.portal.auth.AuthException;
import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
import dev.heysulo.archon.sdk.config.ConfigAdmin;
import dev.heysulo.archon.sdk.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configurations, managed through archon-config as the portal's own verified identity:
 * <ul>
 *     <li>{@code GET /api/configs} every configuration at every level ({@code configs:view})</li>
 *     <li>{@code POST /api/configs}, {@code PUT /api/configs/{id}}, {@code DELETE /api/configs/{id}}
 *     ({@code configs:manage}), recorded as made by the signed-in user</li>
 * </ul>
 */
final class ConfigApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigApi.class);
    private static final Pattern CONFIG_PATH = Pattern.compile("/api/configs/(\\d+)");

    private final Supplier<ConfigAdmin> admin;
    private final Function<String, Optional<Principal>> sessions;

    /**
     * @param admin archon-config's management interface; throws {@link ConfigurationException} while unreachable
     */
    ConfigApi(Supplier<ConfigAdmin> admin, Function<String, Optional<Principal>> sessions) {
        this.admin = admin;
        this.sessions = sessions;
    }

    // Filled by Gson
    static final class ConfigBody {
        String level;
        String group;
        String application;
        String name;
        String type;
        String value;
        Boolean readOnly;
        String description;

        ConfigEntry toEntry(long id) {
            return new ConfigEntry(id, parse(ConfigLevel.class, level, "level"), group, application, name,
                    parse(ConfigType.class, type, "type"), value, readOnly != null && readOnly, description, null,
                    null);
        }

        private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
            if (value == null) {
                return null;
            }
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new AuthException(400, "Unknown " + field + " " + value);
            }
        }
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            Principal user = Http.cookie(exchange, Http.SESSION_COOKIE).flatMap(sessions)
                    .orElseThrow(() -> new AuthException(401, "Sign in first"));
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/configs") || path.equals("/api/configs/")) {
                if (method.equals("GET")) {
                    AuthService.require(user, Permission.CONFIGS_VIEW);
                    Http.sendJson(exchange, 200, admin.get().list());
                } else if (method.equals("POST")) {
                    Http.requireSameOrigin(exchange);
                    AuthService.require(user, Permission.CONFIGS_MANAGE);
                    ConfigBody body = Http.readJson(exchange, ConfigBody.class);
                    Http.sendJson(exchange, 201, admin.get().create(body.toEntry(0), user.username()));
                } else {
                    Http.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            Matcher match = CONFIG_PATH.matcher(path);
            if (!match.matches()) {
                Http.sendError(exchange, 404, "Not found");
                return;
            }
            long id = Long.parseLong(match.group(1));
            Http.requireSameOrigin(exchange);
            AuthService.require(user, Permission.CONFIGS_MANAGE);
            if (method.equals("PUT")) {
                ConfigBody body = Http.readJson(exchange, ConfigBody.class);
                Http.sendJson(exchange, 200, admin.get().update(body.toEntry(id), user.username()));
            } else if (method.equals("DELETE")) {
                admin.get().delete(id, user.username());
                Http.sendNoContent(exchange);
            } else {
                Http.sendError(exchange, 405, "Method not allowed");
            }
        } catch (AuthException e) {
            Http.sendError(exchange, e.status(), e.getMessage());
        } catch (ConfigurationException e) {
            // archon-config's own refusals are explained; an unreachable server is a 503
            Http.sendError(exchange, e.isUnavailable() ? 503 : 400, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Failed {} {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            Http.sendError(exchange, 500, "Something went wrong");
        }
    }
}
