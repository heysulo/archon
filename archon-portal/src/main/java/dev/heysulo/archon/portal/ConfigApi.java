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
import dev.heysulo.archon.sdk.config.ConfigReplica;
import dev.heysulo.archon.sdk.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configurations, managed through archon-config as the portal's own verified identity:
 * <ul>
 *     <li>{@code GET /api/configs?q=&level=&offset=&limit=} one page of configurations at every level, from this
 *     replica's memory, with the total matching ({@code configs:view})</li>
 *     <li>{@code GET /api/configs/effective?group=&application=} what an instance of that application sees: for
 *     each name the configuration that wins, application over group over global, and the ones it overrides
 *     ({@code configs:view})</li>
 *     <li>{@code POST /api/configs}, {@code PUT /api/configs/{id}}, {@code DELETE /api/configs/{id}}
 *     ({@code configs:manage}), recorded as made by the signed-in user</li>
 * </ul>
 */
final class ConfigApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigApi.class);
    private static final Pattern CONFIG_PATH = Pattern.compile("/api/configs/(\\d+)");
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    /** Where configurations are read from and changed through. */
    interface Source {
        /** Every configuration, in memory, in the order they are listed. */
        ConfigReplica replica();

        /** Whether the replica has received everything at least once. */
        boolean synced();

        /** @throws ConfigurationException while archon-config cannot be reached */
        ConfigAdmin admin();
    }

    /**
     * A configuration an application's instances see.
     *
     * @param effective  the one that wins
     * @param overridden the less specific ones of the same name it hides, most specific first
     */
    record Effective(ConfigEntry effective, List<ConfigEntry> overridden) {
    }

    /** What an instance of {@code group}:{@code application} sees, by name. */
    static List<Effective> effective(java.util.Collection<ConfigEntry> entries, String group, String application) {
        java.util.Map<String, List<ConfigEntry>> byName = new java.util.TreeMap<>();
        for (ConfigEntry entry : entries) {
            boolean seen = switch (entry.level()) {
                case GLOBAL -> true;
                case GROUP -> entry.group().equals(group);
                case APPLICATION -> entry.group().equals(group) && entry.application().equals(application);
            };
            if (seen) {
                byName.computeIfAbsent(entry.name(), key -> new java.util.ArrayList<>()).add(entry);
            }
        }
        return byName.values().stream().map(list -> {
            list.sort(java.util.Comparator.comparingInt((ConfigEntry entry) -> entry.level().ordinal()).reversed());
            return new Effective(list.get(0), List.copyOf(list.subList(1, list.size())));
        }).toList();
    }

    /** One page of a list, and how many match in all. */
    record Page(List<ConfigEntry> items, int total, int offset, int limit) {
    }

    private final Source source;
    private final Function<String, Optional<Principal>> sessions;

    ConfigApi(Source source, Function<String, Optional<Principal>> sessions) {
        this.source = source;
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

        static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
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

    /** Filters in one pass over the sorted entries, keeping only the page asked for. */
    static Page page(Collection<ConfigEntry> entries, Map<String, String> parameters) {
        String query = parameters.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        ConfigLevel level = ConfigBody.parse(ConfigLevel.class, blankToNull(parameters.get("level")), "level");
        int offset = number(parameters.get("offset"), 0, 0, Integer.MAX_VALUE);
        int limit = number(parameters.get("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT);
        List<ConfigEntry> items = new ArrayList<>();
        int total = 0;
        for (ConfigEntry entry : entries) {
            if ((level == null || entry.level() == level) && matches(entry, query)) {
                if (total >= offset && items.size() < limit) {
                    items.add(entry);
                }
                total++;
            }
        }
        return new Page(items, total, offset, limit);
    }

    private static boolean matches(ConfigEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        for (String field : new String[]{entry.name(), entry.group(), entry.application(), entry.value(),
                entry.description(), entry.type().name()}) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static int number(String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            throw new AuthException(400, "Not a number: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
                    if (!source.synced()) {
                        throw ConfigurationException.unavailable("Configurations are still loading", null);
                    }
                    Http.sendJson(exchange, 200, page(source.replica().entries(),
                            Http.queryParameters(exchange.getRequestURI())));
                } else if (method.equals("POST")) {
                    Http.requireSameOrigin(exchange);
                    AuthService.require(user, Permission.CONFIGS_MANAGE);
                    ConfigBody body = Http.readJson(exchange, ConfigBody.class);
                    Http.sendJson(exchange, 201, source.admin().create(body.toEntry(0), user.username()));
                } else {
                    Http.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            if (path.equals("/api/configs/effective")) {
                if (!method.equals("GET")) {
                    Http.sendError(exchange, 405, "Method not allowed");
                    return;
                }
                AuthService.require(user, Permission.CONFIGS_VIEW);
                if (!source.synced()) {
                    throw ConfigurationException.unavailable("Configurations are still loading", null);
                }
                java.util.Map<String, String> query = Http.queryParameters(exchange.getRequestURI());
                String group = query.getOrDefault("group", "");
                String application = query.getOrDefault("application", "");
                if (group.isBlank() || application.isBlank()) {
                    throw new AuthException(400, "Name the group and application");
                }
                Http.sendJson(exchange, 200, effective(source.replica().entries(), group, application));
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
                Http.sendJson(exchange, 200, source.admin().update(body.toEntry(id), user.username()));
            } else if (method.equals("DELETE")) {
                source.admin().delete(id, user.username());
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
