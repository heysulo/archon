package dev.heysulo.archon.portal.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signing in, sessions, the first-admin setup, and the rules for managing users.
 *
 * <p>Session tokens and setup tokens are random, sent to the browser once, and stored only as SHA-256 hashes.
 */
public final class AuthService {
    public static final Duration SESSION_IDLE_TIMEOUT = Duration.ofHours(12);
    public static final Duration SESSION_MAX_AGE = Duration.ofDays(7);
    static final Duration SETUP_TOKEN_LIFETIME = Duration.ofHours(24);
    // Saves a database write on every request; a session's idle clock moves at most this often
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    static final String ADMIN_ROLE = "admin";
    static final int MIN_PASSWORD_LENGTH = 10;
    static final int MAX_PASSWORD_LENGTH = 256;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
    private static final Pattern ROLE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_ROLE_DESCRIPTION = 255;
    private static final int MAX_DISPLAY_NAME = 128;
    private static final String BAD_LOGIN = "Wrong username or password";
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    private final AuthStore store;
    private final PasswordHasher hasher;
    private final LoginThrottle throttle;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    // Checked against when a username does not exist, so a missing account takes as long as a wrong password
    private final String decoyHash;

    public AuthService(AuthStore store, PasswordHasher hasher, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.clock = clock;
        this.throttle = new LoginThrottle(clock);
        this.decoyHash = hasher.hash("decoy-password-for-timing");
    }

    /** A new session: the token for the browser's cookie, and who it belongs to. */
    public record Session(String token, Principal principal) {
    }

    /**
     * A role as the portal's API shows it.
     *
     * @param builtIn whether it is the admin role, which holds every permission and cannot be changed or deleted
     * @param users   the usernames holding it
     */
    public record RoleView(String id, String description, Set<String> permissions, boolean builtIn,
                           List<String> users) {
        public RoleView {
            permissions = Set.copyOf(permissions);
            users = List.copyOf(users);
        }
    }

    /** A user as the portal's API shows it, without the password hash. */
    public record UserView(long id, String username, String displayName, boolean enabled, Set<String> roles,
                           String createdAt, String lastLoginAt) {
        public UserView {
            roles = Set.copyOf(roles);
        }

        static UserView of(UserRecord user) {
            return new UserView(user.id(), user.username(), user.displayName(), user.enabled(), user.roles(),
                    String.valueOf(user.createdAt()),
                    user.lastLoginAt() == null ? null : user.lastLoginAt().toString());
        }
    }

    // ---------------- setup ----------------

    public boolean isSetupRequired() {
        return store.countUsers() == 0;
    }

    /**
     * Creates a one-time setup token while no users exist, for the operator to read from the logs.
     *
     * @return the token, or empty if users exist
     */
    public Optional<String> createSetupToken() {
        if (!isSetupRequired()) {
            return Optional.empty();
        }
        String token = randomToken();
        Instant now = clock.instant();
        store.addSetupToken(sha256(token), now, now.plus(SETUP_TOKEN_LIFETIME));
        return Optional.of(token);
    }

    /** Creates the first admin, if the token is valid and no users exist yet, and signs them in. */
    public synchronized Session completeSetup(String setupToken, String username, String displayName,
            String password) {
        if (!isSetupRequired()) {
            throw AuthException.conflict("Setup is already complete");
        }
        if (setupToken == null || !store.isSetupTokenValid(sha256(setupToken.trim()), clock.instant())) {
            throw AuthException.forbidden("The setup token is wrong or has expired");
        }
        String name = validUsername(username);
        long id = store.createUser(name, validDisplayName(displayName, name), hasher.hash(validPassword(password)),
                List.of(ADMIN_ROLE), clock.instant());
        store.clearSetupTokens();
        LOGGER.info("First admin {} created through setup", name);
        return startSession(store.findUserById(id).orElseThrow());
    }

    // ---------------- signing in ----------------

    public Session login(String username, String password) {
        String key = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        Duration wait = throttle.waitFor(key);
        if (!wait.isZero()) {
            throw AuthException.tooManyRequests("Too many failed attempts. Try again in "
                    + Math.max(1, wait.toSeconds()) + " s");
        }
        Optional<UserRecord> user = store.findUserByUsername(key);
        String candidate = password == null ? "" : password;
        // Hash even for unknown users, so response times do not reveal which usernames exist
        boolean matches = hasher.verify(candidate, user.map(UserRecord::passwordHash).orElse(decoyHash));
        if (user.isEmpty() || !matches || !user.get().enabled()) {
            throttle.failed(key);
            throw AuthException.unauthorized(BAD_LOGIN);
        }
        throttle.succeeded(key);
        store.recordLogin(user.get().id(), clock.instant());
        LOGGER.info("{} signed in", key);
        return startSession(user.get());
    }

    public void logout(String token) {
        if (token != null) {
            store.deleteSession(sha256(token));
        }
    }

    /**
     * Finds who a session token belongs to, sliding its idle timeout forward.
     *
     * @return empty if the token is unknown, expired, or its user is gone or disabled
     */
    public Optional<Principal> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256(token);
        Optional<SessionRecord> session = store.findSession(hash);
        Instant now = clock.instant();
        if (session.isEmpty() || !now.isBefore(session.get().expiresAt())) {
            return Optional.empty();
        }
        Optional<UserRecord> user = store.findUserById(session.get().userId());
        if (user.isEmpty() || !user.get().enabled()) {
            store.deleteSession(hash);
            return Optional.empty();
        }
        if (Duration.between(session.get().lastSeenAt(), now).compareTo(TOUCH_INTERVAL) >= 0) {
            store.touchSession(hash, now, expiry(session.get().createdAt(), now));
        }
        return Optional.of(principal(user.get()));
    }

    /** Changes the signed-in user's own password and signs them out everywhere else. */
    public void changeOwnPassword(Principal principal, String token, String currentPassword, String newPassword) {
        UserRecord user = store.findUserById(principal.userId())
                .orElseThrow(() -> AuthException.unauthorized("Not signed in"));
        if (!hasher.verify(currentPassword == null ? "" : currentPassword, user.passwordHash())) {
            throw AuthException.forbidden("The current password is wrong");
        }
        store.setPasswordHash(user.id(), hasher.hash(validPassword(newPassword)), clock.instant());
        store.deleteSessionsOf(user.id(), token == null ? null : sha256(token));
    }

    // ---------------- managing users ----------------

    public List<UserView> listUsers(Principal actor) {
        require(actor, Permission.USERS_VIEW);
        return store.listUsers().stream().map(UserView::of).toList();
    }

    /** Every role and who holds it, for those who see roles, or manage users and so assign them. */
    public List<RoleView> listRoles(Principal actor) {
        if (!actor.can(Permission.USERS_MANAGE) && !actor.can(Permission.ROLES_VIEW)) {
            throw AuthException.forbidden("You do not have permission to see roles");
        }
        return roleViews();
    }

    private List<RoleView> roleViews() {
        List<UserRecord> users = store.listUsers();
        return store.listRoles().stream().map(role -> new RoleView(role.id(), role.description(), role.permissions(),
                ADMIN_ROLE.equals(role.id()), users.stream().filter(user -> user.roles().contains(role.id()))
                        .map(UserRecord::username).sorted().toList())).toList();
    }

    // ---------------- managing roles ----------------

    /**
     * Adds a role. Its permissions must be ones the actor holds: no one can grant more than they have.
     */
    public synchronized RoleView createRole(Principal actor, String id, String description,
            Collection<String> permissions) {
        require(actor, Permission.ROLES_MANAGE);
        String roleId = id == null ? "" : id.trim();
        if (!ROLE_ID.matcher(roleId).matches()) {
            throw AuthException.badRequest("A role id is 1 to 64 lowercase letters, digits, dots, dashes or "
                    + "underscores, starting with a letter or digit");
        }
        if (findRole(roleId).isPresent()) {
            throw AuthException.conflict("A role named " + roleId + " exists");
        }
        Set<String> granted = grantable(actor, permissions);
        store.createRole(roleId, validRoleDescription(description), granted, clock.instant());
        LOGGER.info("{} created role {} with {}", actor.username(), roleId, granted);
        return view(roleId);
    }

    /**
     * Changes a role's description and permissions, for everyone holding it, from their next request. The admin role
     * cannot change. No change may leave nobody able to manage users or roles.
     */
    public synchronized RoleView updateRole(Principal actor, String id, String description,
            Collection<String> permissions) {
        require(actor, Permission.ROLES_MANAGE);
        RoleRecord role = findRole(id).orElseThrow(() -> AuthException.notFound("No such role"));
        if (ADMIN_ROLE.equals(role.id())) {
            throw AuthException.badRequest("The admin role is built in and holds every permission");
        }
        Set<String> granted = grantable(actor, permissions);
        Set<String> removed = new HashSet<>(role.permissions());
        removed.removeAll(granted);
        if (!removed.isEmpty()) {
            requireStillManageable(role.id(), granted, actor);
        }
        store.updateRole(role.id(), validRoleDescription(description), granted);
        LOGGER.info("{} changed role {} to {}", actor.username(), role.id(), granted);
        return view(role.id());
    }

    /** Deletes a role nobody holds. The admin role stays. */
    public synchronized void deleteRole(Principal actor, String id) {
        require(actor, Permission.ROLES_MANAGE);
        RoleRecord role = findRole(id).orElseThrow(() -> AuthException.notFound("No such role"));
        if (ADMIN_ROLE.equals(role.id())) {
            throw AuthException.badRequest("The admin role is built in and cannot be deleted");
        }
        List<String> holders = store.listUsers().stream().filter(user -> user.roles().contains(role.id()))
                .map(UserRecord::username).sorted().toList();
        if (!holders.isEmpty()) {
            throw AuthException.conflict("Take the role away from " + String.join(", ", holders) + " first");
        }
        store.deleteRole(role.id());
        LOGGER.info("{} deleted role {}", actor.username(), role.id());
    }

    private Optional<RoleRecord> findRole(String id) {
        return store.listRoles().stream().filter(role -> role.id().equals(id)).findFirst();
    }

    private RoleView view(String id) {
        return roleViews().stream().filter(role -> role.id().equals(id)).findFirst().orElseThrow();
    }

    /** Known permissions only, at least one, never {@code *}, and only ones the actor holds. */
    private static Set<String> grantable(Principal actor, Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw AuthException.badRequest("Give the role at least one permission");
        }
        Set<String> granted = new HashSet<>();
        for (String key : permissions) {
            Permission permission = Permission.byKey(key)
                    .orElseThrow(() -> AuthException.badRequest("Unknown permission " + key));
            if (!actor.can(permission)) {
                throw AuthException.forbidden("You cannot grant " + permission.key() + ", which you do not hold");
            }
            granted.add(permission.key());
        }
        return Set.copyOf(granted);
    }

    private static String validRoleDescription(String description) {
        String trimmed = description == null ? "" : description.trim();
        if (trimmed.length() > MAX_ROLE_DESCRIPTION) {
            throw AuthException.badRequest("A role description is at most " + MAX_ROLE_DESCRIPTION + " characters");
        }
        return trimmed;
    }

    /**
     * Refuses a change to a role's permissions that would leave no enabled user able to manage users, or roles, or
     * take role management from the actor making it.
     */
    private void requireStillManageable(String roleId, Set<String> newPermissions, Principal actor) {
        Map<String, Set<String>> permissionsByRole = new HashMap<>();
        store.listRoles().forEach(role -> permissionsByRole.put(role.id(), role.permissions()));
        permissionsByRole.put(roleId, newPermissions);
        List<UserRecord> enabled = store.listUsers().stream().filter(UserRecord::enabled).toList();
        for (Permission needed : List.of(Permission.USERS_MANAGE, Permission.ROLES_MANAGE)) {
            boolean someoneCan = enabled.stream().anyMatch(user -> grants(user.roles(), permissionsByRole, needed));
            if (!someoneCan) {
                throw AuthException.badRequest("At least one enabled user must be able to "
                        + needed.description().toLowerCase(Locale.ROOT));
            }
        }
        UserRecord self = store.findUserById(actor.userId()).orElse(null);
        if (self != null && !grants(self.roles(), permissionsByRole, Permission.ROLES_MANAGE)) {
            throw AuthException.badRequest("You cannot remove your own role management");
        }
    }

    private static boolean grants(Set<String> roles, Map<String, Set<String>> permissionsByRole,
            Permission permission) {
        return roles.stream().map(role -> permissionsByRole.getOrDefault(role, Set.of())).anyMatch(granted ->
                granted.contains(Permission.ALL) || granted.contains(permission.key()));
    }

    public synchronized UserView createUser(Principal actor, String username, String displayName, String password,
            Collection<String> roles) {
        require(actor, Permission.USERS_MANAGE);
        String name = validUsername(username);
        if (store.findUserByUsername(name).isPresent()) {
            throw AuthException.conflict("The username " + name + " is taken");
        }
        Set<String> validRoles = validRoles(roles);
        requireAssignable(actor, validRoles, Set.of());
        long id = store.createUser(name, validDisplayName(displayName, name), hasher.hash(validPassword(password)),
                validRoles, clock.instant());
        LOGGER.info("{} created user {} with roles {}", actor.username(), name, validRoles);
        return UserView.of(store.findUserById(id).orElseThrow());
    }

    public synchronized UserView updateUser(Principal actor, long id, String displayName, boolean enabled,
            Collection<String> roles) {
        require(actor, Permission.USERS_MANAGE);
        UserRecord user = store.findUserById(id).orElseThrow(() -> AuthException.notFound("No such user"));
        Set<String> validRoles = validRoles(roles);
        requireAssignable(actor, validRoles, user.roles());
        if (id == actor.userId() && (!enabled || !grantsUserManagement(validRoles))) {
            throw AuthException.badRequest("You cannot disable yourself or remove your own user management");
        }
        boolean losesManagement = isActiveManager(user) && (!enabled || !grantsUserManagement(validRoles));
        if (losesManagement && countActiveManagers() <= 1) {
            throw AuthException.badRequest("At least one enabled user must be able to manage users");
        }
        store.updateUser(id, validDisplayName(displayName, user.username()), enabled, validRoles, clock.instant());
        if (!enabled) {
            store.deleteSessionsOf(id, null);
        }
        LOGGER.info("{} updated user {}: enabled {}, roles {}", actor.username(), user.username(), enabled,
                validRoles);
        return UserView.of(store.findUserById(id).orElseThrow());
    }

    /** Sets another user's password and signs them out everywhere. */
    public void resetPassword(Principal actor, long id, String newPassword) {
        require(actor, Permission.USERS_MANAGE);
        UserRecord user = store.findUserById(id).orElseThrow(() -> AuthException.notFound("No such user"));
        store.setPasswordHash(id, hasher.hash(validPassword(newPassword)), clock.instant());
        store.deleteSessionsOf(id, null);
        LOGGER.info("{} reset the password of {}", actor.username(), user.username());
    }

    public synchronized void deleteUser(Principal actor, long id) {
        require(actor, Permission.USERS_MANAGE);
        if (id == actor.userId()) {
            throw AuthException.badRequest("You cannot delete yourself");
        }
        UserRecord user = store.findUserById(id).orElseThrow(() -> AuthException.notFound("No such user"));
        if (isActiveManager(user) && countActiveManagers() <= 1) {
            throw AuthException.badRequest("At least one enabled user must be able to manage users");
        }
        store.deleteUser(id);
        LOGGER.info("{} deleted user {}", actor.username(), user.username());
    }

    public int cleanUp() {
        return store.deleteExpired(clock.instant());
    }

    // ---------------- rules ----------------

    public static void require(Principal actor, Permission permission) {
        if (!actor.can(permission)) {
            throw AuthException.forbidden("You do not have permission to " + permission.description().toLowerCase(
                    Locale.ROOT));
        }
    }

    private Session startSession(UserRecord user) {
        String token = randomToken();
        Instant now = clock.instant();
        store.createSession(sha256(token), user.id(), now, expiry(now, now));
        return new Session(token, principal(user));
    }

    // Idle timeout from the last use, never past the session's maximum age
    private static Instant expiry(Instant created, Instant now) {
        Instant idle = now.plus(SESSION_IDLE_TIMEOUT);
        Instant max = created.plus(SESSION_MAX_AGE);
        return idle.isBefore(max) ? idle : max;
    }

    private Principal principal(UserRecord user) {
        return new Principal(user.id(), user.username(), user.displayName(), user.roles(),
                permissionsOf(user.roles()));
    }

    private Set<String> permissionsOf(Set<String> roles) {
        Set<String> permissions = new HashSet<>();
        store.listRoles().stream().filter(role -> roles.contains(role.id()))
                .forEach(role -> permissions.addAll(role.permissions()));
        return permissions;
    }

    /**
     * Only roles whose permissions the actor holds can be given, so managing users never lets anyone gain more than
     * they have. Roles the user already holds may stay.
     */
    private void requireAssignable(Principal actor, Set<String> roles, Set<String> alreadyHeld) {
        for (RoleRecord role : store.listRoles()) {
            if (!roles.contains(role.id()) || alreadyHeld.contains(role.id())) {
                continue;
            }
            boolean holdsAll = actor.permissions().contains(Permission.ALL) || role.permissions().stream()
                    .allMatch(permission -> Permission.byKey(permission).map(actor::can).orElse(false));
            if (!holdsAll) {
                throw AuthException.forbidden("You cannot give the " + role.id()
                        + " role, which has permissions you do not hold");
            }
        }
    }

    private boolean grantsUserManagement(Set<String> roles) {
        Set<String> permissions = permissionsOf(roles);
        return permissions.contains(Permission.ALL) || permissions.contains(Permission.USERS_MANAGE.key());
    }

    private boolean isActiveManager(UserRecord user) {
        return user.enabled() && grantsUserManagement(user.roles());
    }

    private long countActiveManagers() {
        return store.listUsers().stream().filter(this::isActiveManager).count();
    }

    private Set<String> validRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw AuthException.badRequest("Give the user at least one role");
        }
        Set<String> known = new HashSet<>();
        store.listRoles().forEach(role -> known.add(role.id()));
        for (String role : roles) {
            if (!known.contains(role)) {
                throw AuthException.badRequest("Unknown role " + role);
            }
        }
        return Set.copyOf(roles);
    }

    private static String validUsername(String username) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw AuthException.badRequest("Usernames are 2 to 64 lowercase letters, digits, dots, dashes or "
                    + "underscores, starting with a letter or digit");
        }
        return name;
    }

    private static String validDisplayName(String displayName, String fallback) {
        String name = displayName == null || displayName.isBlank() ? fallback : displayName.trim();
        if (name.length() > MAX_DISPLAY_NAME) {
            throw AuthException.badRequest("Names are at most " + MAX_DISPLAY_NAME + " characters");
        }
        return name;
    }

    private static String validPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw AuthException.badRequest("Passwords are " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH
                    + " characters");
        }
        return password;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
