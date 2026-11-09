package dev.heysulo.archon.portal;

import com.sun.net.httpserver.HttpExchange;
import dev.heysulo.archon.portal.auth.AuthException;
import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.auth.Principal;
import dev.heysulo.archon.portal.auth.AuthStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sign-in and user management API:
 * <ul>
 *     <li>{@code GET /api/auth/state} whether setup is needed, and who is signed in with which permissions</li>
 *     <li>{@code POST /api/auth/setup} creates the first admin with a setup token from the logs</li>
 *     <li>{@code POST /api/auth/login} and {@code POST /api/auth/logout}</li>
 *     <li>{@code POST /api/auth/password} changes the signed-in user's password</li>
 *     <li>{@code GET, POST /api/users}, {@code PUT, DELETE /api/users/{id}}, {@code POST /api/users/{id}/password}</li>
 *     <li>{@code GET /api/roles} roles and the permissions they grant</li>
 * </ul>
 * Requests that change something must come from the portal's pages; see {@link Http#requireSameOrigin}.
 */
final class AuthApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthApi.class);
    private static final Pattern USER_PATH = Pattern.compile("/api/users/(\\d+)(/password)?");
    private static final Pattern ROLE_PATH = Pattern.compile("/api/roles/([a-z0-9][a-z0-9._-]{0,63})");

    private final AuthService auth;

    AuthApi(AuthService auth) {
        this.auth = auth;
    }

    // Request bodies, filled by Gson
    static final class Credentials {
        String username;
        String password;
    }

    static final class SetupRequest {
        String token;
        String username;
        String displayName;
        String password;
    }

    static final class PasswordChange {
        String currentPassword;
        String newPassword;
    }

    static final class UserRequest {
        String username;
        String displayName;
        String password;
        Boolean enabled;
        List<String> roles;
    }

    static final class PasswordReset {
        String password;
    }

    record Me(long id, String username, String displayName, Set<String> roles, Set<String> permissions) {
        static Me of(Principal principal) {
            return new Me(principal.userId(), principal.username(), principal.displayName(), principal.roles(),
                    principal.permissions());
        }
    }

    record State(boolean setupRequired, Me user) {
    }

    record PermissionView(String key, String description) {
    }

    record RolesView(List<AuthService.RoleView> roles, List<PermissionView> permissions) {
    }

    // Filled by Gson
    static final class RoleRequest {
        String id;
        String description;
        List<String> permissions;
    }

    /** The signed-in user, if the request carries a valid session cookie. */
    Optional<Principal> principal(HttpExchange exchange) {
        return Http.cookie(exchange, Http.SESSION_COOKIE).flatMap(auth::authenticate);
    }

    void handleAuth(HttpExchange exchange) throws IOException {
        guarded(exchange, () -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            switch (method + " " + path) {
                case "GET /api/auth/state" -> Http.sendJson(exchange, 200,
                        new State(auth.isSetupRequired(), principal(exchange).map(Me::of).orElse(null)));
                case "POST /api/auth/setup" -> {
                    Http.requireSameOrigin(exchange);
                    SetupRequest body = Http.readJson(exchange, SetupRequest.class);
                    AuthService.Session session = auth.completeSetup(body.token, body.username, body.displayName,
                            body.password);
                    Http.setSessionCookie(exchange, session.token(), AuthService.SESSION_MAX_AGE);
                    Http.sendJson(exchange, 200, new State(false, Me.of(session.principal())));
                }
                case "POST /api/auth/login" -> {
                    Http.requireSameOrigin(exchange);
                    Credentials body = Http.readJson(exchange, Credentials.class);
                    AuthService.Session session = auth.login(body.username, body.password);
                    Http.setSessionCookie(exchange, session.token(), AuthService.SESSION_MAX_AGE);
                    Http.sendJson(exchange, 200, new State(false, Me.of(session.principal())));
                }
                case "POST /api/auth/logout" -> {
                    Http.requireSameOrigin(exchange);
                    auth.logout(Http.cookie(exchange, Http.SESSION_COOKIE).orElse(null));
                    Http.clearSessionCookie(exchange);
                    Http.sendNoContent(exchange);
                }
                case "POST /api/auth/password" -> {
                    Http.requireSameOrigin(exchange);
                    Principal principal = signedIn(exchange);
                    PasswordChange body = Http.readJson(exchange, PasswordChange.class);
                    auth.changeOwnPassword(principal, Http.cookie(exchange, Http.SESSION_COOKIE).orElse(null),
                            body.currentPassword, body.newPassword);
                    Http.sendNoContent(exchange);
                }
                default -> Http.sendError(exchange, 404, "Not found");
            }
        });
    }

    void handleUsers(HttpExchange exchange) throws IOException {
        guarded(exchange, () -> {
            Principal actor = signedIn(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/users") || path.equals("/api/users/")) {
                if (method.equals("GET")) {
                    Http.sendJson(exchange, 200, auth.listUsers(actor));
                } else if (method.equals("POST")) {
                    Http.requireSameOrigin(exchange);
                    UserRequest body = Http.readJson(exchange, UserRequest.class);
                    Http.sendJson(exchange, 201, auth.createUser(actor, body.username, body.displayName,
                            body.password, body.roles));
                } else {
                    Http.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            Matcher match = USER_PATH.matcher(path);
            if (!match.matches()) {
                Http.sendError(exchange, 404, "Not found");
                return;
            }
            long id = Long.parseLong(match.group(1));
            boolean password = match.group(2) != null;
            Http.requireSameOrigin(exchange);
            if (password && method.equals("POST")) {
                auth.resetPassword(actor, id, Http.readJson(exchange, PasswordReset.class).password);
                Http.sendNoContent(exchange);
            } else if (!password && method.equals("PUT")) {
                UserRequest body = Http.readJson(exchange, UserRequest.class);
                Http.sendJson(exchange, 200, auth.updateUser(actor, id, body.displayName,
                        body.enabled == null || body.enabled, body.roles));
            } else if (!password && method.equals("DELETE")) {
                auth.deleteUser(actor, id);
                Http.sendNoContent(exchange);
            } else {
                Http.sendError(exchange, 405, "Method not allowed");
            }
        });
    }

    /**
     * {@code GET /api/roles} every role with who holds it, and every permission there is; {@code POST /api/roles},
     * {@code PUT /api/roles/{id}} and {@code DELETE /api/roles/{id}} manage them ({@code roles:manage}).
     */
    void handleRoles(HttpExchange exchange) throws IOException {
        guarded(exchange, () -> {
            Principal actor = signedIn(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/roles") || path.equals("/api/roles/")) {
                if (method.equals("GET")) {
                    List<PermissionView> permissions = Arrays.stream(Permission.values())
                            .map(permission -> new PermissionView(permission.key(), permission.description()))
                            .toList();
                    Http.sendJson(exchange, 200, new RolesView(auth.listRoles(actor), permissions));
                } else if (method.equals("POST")) {
                    Http.requireSameOrigin(exchange);
                    RoleRequest body = Http.readJson(exchange, RoleRequest.class);
                    Http.sendJson(exchange, 201, auth.createRole(actor, body.id, body.description,
                            body.permissions));
                } else {
                    Http.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            Matcher match = ROLE_PATH.matcher(path);
            if (!match.matches()) {
                Http.sendError(exchange, 404, "Not found");
                return;
            }
            String id = match.group(1);
            Http.requireSameOrigin(exchange);
            if (method.equals("PUT")) {
                RoleRequest body = Http.readJson(exchange, RoleRequest.class);
                Http.sendJson(exchange, 200, auth.updateRole(actor, id, body.description, body.permissions));
            } else if (method.equals("DELETE")) {
                auth.deleteRole(actor, id);
                Http.sendNoContent(exchange);
            } else {
                Http.sendError(exchange, 405, "Method not allowed");
            }
        });
    }

    private Principal signedIn(HttpExchange exchange) {
        return principal(exchange).orElseThrow(() -> new AuthException(401, "Sign in first"));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    // Turns refusals into their status codes, and database failures into a 503 without their details
    private static void guarded(HttpExchange exchange, Action action) throws IOException {
        try {
            action.run();
        } catch (AuthException e) {
            Http.sendError(exchange, e.status(), e.getMessage());
        } catch (AuthStore.StoreException e) {
            LOGGER.error("Database error on {} {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    e);
            Http.sendError(exchange, 503, "The portal cannot reach its database. Try again shortly");
        } catch (RuntimeException e) {
            LOGGER.error("Failed {} {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            Http.sendError(exchange, 500, "Something went wrong");
        }
    }

}
