package dev.heysulo.archon.portal.auth;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class AuthServiceTest {

    private static final String PASSWORD = "correct horse battery";

    private TestDatabase database;
    private TestDatabase.TestClock clock;
    private AuthService auth;

    @BeforeMethod
    public void setUp() throws Exception {
        database = new TestDatabase();
        clock = new TestDatabase.TestClock();
        auth = new AuthService(database.store(), new PasswordHasher(1_000), clock);
    }

    @AfterMethod
    public void tearDown() {
        database.close();
    }

    private AuthService.Session firstAdmin() {
        String token = auth.createSetupToken().orElseThrow();
        return auth.completeSetup(token, "Alice", "Alice Admin", PASSWORD);
    }

    // ---------------- setup ----------------

    @Test
    public void setupCreatesTheFirstAdminOnceWithAValidToken() {
        Assert.assertTrue(auth.isSetupRequired());
        String token = auth.createSetupToken().orElseThrow();

        AuthException wrong = Assert.expectThrows(AuthException.class,
                () -> auth.completeSetup("not-the-token", "alice", null, PASSWORD));
        Assert.assertEquals(wrong.status(), 403);

        AuthService.Session session = auth.completeSetup(token, "Alice", "Alice Admin", PASSWORD);
        Assert.assertEquals(session.principal().username(), "alice");
        Assert.assertTrue(session.principal().can(Permission.USERS_MANAGE));
        Assert.assertTrue(session.principal().can(Permission.PROCESSES_VIEW));
        Assert.assertFalse(auth.isSetupRequired());
        Assert.assertTrue(auth.createSetupToken().isEmpty(), "no setup tokens once users exist");

        AuthException again = Assert.expectThrows(AuthException.class,
                () -> auth.completeSetup(token, "mallory", null, PASSWORD));
        Assert.assertEquals(again.status(), 409);
    }

    // Every portal replica logs its own token; any of them must work on any replica
    @Test
    public void anyReplicasSetupTokenWorks() {
        auth.createSetupToken().orElseThrow();
        String second = auth.createSetupToken().orElseThrow();
        Assert.assertEquals(auth.completeSetup(second, "alice", null, PASSWORD).principal().username(), "alice");
    }

    @Test
    public void setupTokensExpire() {
        String token = auth.createSetupToken().orElseThrow();
        clock.advance(AuthService.SETUP_TOKEN_LIFETIME.plusSeconds(1));
        Assert.assertEquals(Assert.expectThrows(AuthException.class,
                () -> auth.completeSetup(token, "alice", null, PASSWORD)).status(), 403);
    }

    // ---------------- signing in ----------------

    @Test
    public void signsInWithTheRightPasswordOnly() {
        firstAdmin();
        AuthService.Session session = auth.login(" ALICE ", PASSWORD);
        Assert.assertEquals(auth.authenticate(session.token()).orElseThrow().username(), "alice");

        Assert.assertEquals(Assert.expectThrows(AuthException.class, () -> auth.login("alice", "wrong password"))
                .status(), 401);
        AuthException unknown = Assert.expectThrows(AuthException.class, () -> auth.login("bob", PASSWORD));
        Assert.assertEquals(unknown.status(), 401);
        Assert.assertEquals(unknown.getMessage(), "Wrong username or password", "the same message either way");
    }

    @Test
    public void slowsDownRepeatedFailures() {
        firstAdmin();
        for (int attempt = 0; attempt < LoginThrottle.FREE_ATTEMPTS; attempt++) {
            Assert.expectThrows(AuthException.class, () -> auth.login("alice", "wrong password"));
        }
        AuthException blocked = Assert.expectThrows(AuthException.class, () -> auth.login("alice", PASSWORD));
        Assert.assertEquals(blocked.status(), 429);

        clock.advance(Duration.ofSeconds(2));
        Assert.assertNotNull(auth.login("alice", PASSWORD).token(), "allowed again after the wait");
    }

    @Test
    public void signsOutAndRejectsUnknownTokens() {
        AuthService.Session session = firstAdmin();
        Assert.assertTrue(auth.authenticate("made-up-token").isEmpty());
        auth.logout(session.token());
        Assert.assertTrue(auth.authenticate(session.token()).isEmpty());
    }

    @Test
    public void sessionsExpireWhenIdleAndAtTheirMaximumAge() {
        AuthService.Session idle = firstAdmin();
        clock.advance(AuthService.SESSION_IDLE_TIMEOUT.plusMinutes(1));
        Assert.assertTrue(auth.authenticate(idle.token()).isEmpty(), "idle too long");

        AuthService.Session busy = auth.login("alice", PASSWORD);
        Duration step = Duration.ofHours(6);
        for (Duration used = Duration.ZERO; used.plus(step).compareTo(AuthService.SESSION_MAX_AGE) < 0;
                used = used.plus(step)) {
            clock.advance(step);
            Assert.assertTrue(auth.authenticate(busy.token()).isPresent(), "kept alive by use");
        }
        clock.advance(step);
        Assert.assertTrue(auth.authenticate(busy.token()).isEmpty(), "never past the maximum age");
    }

    @Test
    public void changingYourPasswordSignsYouOutEverywhereElse() {
        AuthService.Session here = firstAdmin();
        AuthService.Session elsewhere = auth.login("alice", PASSWORD);

        Assert.assertEquals(Assert.expectThrows(AuthException.class, () -> auth.changeOwnPassword(here.principal(),
                here.token(), "wrong password", "a brand new password")).status(), 403);
        auth.changeOwnPassword(here.principal(), here.token(), PASSWORD, "a brand new password");

        Assert.assertTrue(auth.authenticate(here.token()).isPresent());
        Assert.assertTrue(auth.authenticate(elsewhere.token()).isEmpty());
        Assert.assertEquals(auth.login("alice", "a brand new password").principal().username(), "alice");
    }

    // ---------------- managing users ----------------

    @Test
    public void managesUsers() {
        Principal admin = firstAdmin().principal();
        AuthService.UserView bob = auth.createUser(admin, "bob", "Bob", "bobs long password", List.of("admin"));
        Assert.assertEquals(auth.listUsers(admin).stream().map(AuthService.UserView::username).toList(),
                List.of("alice", "bob"));

        AuthService.Session bobSession = auth.login("bob", "bobs long password");
        auth.updateUser(admin, bob.id(), "Robert", false, List.of("admin"));
        Assert.assertTrue(auth.authenticate(bobSession.token()).isEmpty(), "disabling signs the user out");
        Assert.assertEquals(Assert.expectThrows(AuthException.class,
                () -> auth.login("bob", "bobs long password")).status(), 401);

        auth.updateUser(admin, bob.id(), "Robert", true, List.of("admin"));
        auth.resetPassword(admin, bob.id(), "another long password");
        Assert.assertEquals(auth.login("bob", "another long password").principal().displayName(), "Robert");

        auth.deleteUser(admin, bob.id());
        Assert.assertEquals(auth.listUsers(admin).size(), 1);
    }

    @Test
    public void protectsTheActingAdmin() {
        Principal admin = firstAdmin().principal();
        Assert.assertEquals(Assert.expectThrows(AuthException.class, () -> auth.deleteUser(admin, admin.userId()))
                .status(), 400);
        Assert.assertEquals(Assert.expectThrows(AuthException.class,
                () -> auth.updateUser(admin, admin.userId(), "Alice", false, List.of("admin"))).status(), 400);
    }

    @Test
    public void validatesInput() {
        Principal admin = firstAdmin().principal();
        Assert.assertEquals(status(() -> auth.createUser(admin, "Bad Name!", null, "long enough pass",
                List.of("admin"))), 400);
        Assert.assertEquals(status(() -> auth.createUser(admin, "carol", null, "short", List.of("admin"))), 400);
        Assert.assertEquals(status(() -> auth.createUser(admin, "carol", null, "long enough pass",
                List.of("no-such-role"))), 400);
        Assert.assertEquals(status(() -> auth.createUser(admin, "carol", null, "long enough pass", List.of())), 400);
        Assert.assertEquals(status(() -> auth.createUser(admin, "ALICE", null, "long enough pass",
                List.of("admin"))), 409);
    }

    // Roles are data: one granting only processes:view lets its users look but not manage
    @Test
    public void permissionsComeFromRoles() throws Exception {
        database.addRole("viewer", Permission.PROCESSES_VIEW.key());
        Principal admin = firstAdmin().principal();
        auth.createUser(admin, "victor", null, "victors long password", List.of("viewer"));

        Principal viewer = auth.login("victor", "victors long password").principal();
        Assert.assertTrue(viewer.can(Permission.PROCESSES_VIEW));
        Assert.assertFalse(viewer.can(Permission.USERS_MANAGE));
        Assert.assertEquals(status(() -> auth.listUsers(viewer)), 403);
    }

    // ---------------- managing roles ----------------

    @Test
    public void managesRolesAndAppliesChangesAtOnce() {
        Principal admin = firstAdmin().principal();
        AuthService.RoleView operator = auth.createRole(admin, " operator ", "Runs the system",
                List.of(Permission.PROCESSES_VIEW.key()));
        Assert.assertEquals(operator.id(), "operator");
        Assert.assertFalse(operator.builtIn());
        auth.createUser(admin, "olga", null, "olgas long password", List.of("operator"));
        AuthService.Session olga = auth.login("olga", "olgas long password");
        Assert.assertFalse(olga.principal().can(Permission.CONFIGS_VIEW));

        AuthService.RoleView changed = auth.updateRole(admin, "operator", "Runs and tunes the system",
                List.of(Permission.PROCESSES_VIEW.key(), Permission.CONFIGS_VIEW.key()));
        Assert.assertEquals(changed.users(), List.of("olga"));
        Assert.assertTrue(auth.authenticate(olga.token()).orElseThrow().can(Permission.CONFIGS_VIEW),
                "a signed-in user gets the new permission on the next request");
    }

    @Test
    public void validatesRoles() {
        Principal admin = firstAdmin().principal();
        Assert.assertEquals(status(() -> auth.createRole(admin, "Has Space", "", List.of("processes:view"))), 400);
        Assert.assertEquals(status(() -> auth.createRole(admin, "empty", "", List.of())), 400);
        Assert.assertEquals(status(() -> auth.createRole(admin, "wild", "", List.of("*"))), 400,
                "only the admin role holds every permission");
        Assert.assertEquals(status(() -> auth.createRole(admin, "typo", "", List.of("procesess:view"))), 400);
        Assert.assertEquals(status(() -> auth.createRole(admin, "admin", "", List.of("processes:view"))), 409);
        Assert.assertEquals(status(() -> auth.createRole(admin, "long", "x".repeat(300),
                List.of("processes:view"))), 400);
        Assert.assertEquals(status(() -> auth.updateRole(admin, "missing", "", List.of("processes:view"))), 404);
    }

    @Test
    public void theAdminRoleIsBuiltIn() {
        Principal admin = firstAdmin().principal();
        Assert.assertTrue(auth.listRoles(admin).stream().filter(role -> role.id().equals("admin")).findFirst()
                .orElseThrow().builtIn());
        Assert.assertEquals(status(() -> auth.updateRole(admin, "admin", "", List.of("processes:view"))), 400);
        Assert.assertEquals(status(() -> auth.deleteRole(admin, "admin")), 400);
    }

    @Test
    public void deletesOnlyRolesNobodyHolds() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "viewer", "", List.of(Permission.PROCESSES_VIEW.key()));
        AuthService.UserView vic = auth.createUser(admin, "vic", null, "vics long password", List.of("viewer"));
        AuthException refused = Assert.expectThrows(AuthException.class, () -> auth.deleteRole(admin, "viewer"));
        Assert.assertEquals(refused.status(), 409);
        Assert.assertTrue(refused.getMessage().contains("vic"), refused.getMessage());

        auth.deleteUser(admin, vic.id());
        auth.deleteRole(admin, "viewer");
        Assert.assertTrue(auth.listRoles(admin).stream().noneMatch(role -> role.id().equals("viewer")));
    }

    // Managing roles or users must never let anyone gain permissions they lack
    @Test
    public void nobodyGrantsWhatTheyDoNotHold() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "role-admin", "",
                List.of(Permission.ROLES_MANAGE.key(), Permission.USERS_MANAGE.key(), Permission.PROCESSES_VIEW.key()));
        auth.createRole(admin, "configurer", "", List.of(Permission.CONFIGS_MANAGE.key()));
        auth.createUser(admin, "rita", null, "ritas long password", List.of("role-admin"));
        Principal rita = auth.login("rita", "ritas long password").principal();

        Assert.assertEquals(status(() -> auth.createRole(rita, "sneaky", "",
                List.of(Permission.CONFIGS_MANAGE.key()))), 403, "a role with a permission she lacks");
        auth.createRole(rita, "watcher", "", List.of(Permission.PROCESSES_VIEW.key()));
        Assert.assertEquals(status(() -> auth.createUser(rita, "sam", null, "sams long password",
                List.of("configurer"))), 403, "a role with a permission she lacks");
        Assert.assertEquals(status(() -> auth.createUser(rita, "sam", null, "sams long password",
                List.of("admin"))), 403, "the admin role");
        auth.createUser(rita, "sam", null, "sams long password", List.of("watcher"));
    }

    // Editing a user may keep a role the editor could not give
    @Test
    public void keepingARoleIsNotGivingIt() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "user-admin", "", List.of(Permission.USERS_MANAGE.key()));
        auth.createRole(admin, "configurer", "", List.of(Permission.CONFIGS_MANAGE.key()));
        AuthService.UserView carl = auth.createUser(admin, "carl", null, "carls long password",
                List.of("configurer"));
        auth.createUser(admin, "uma", null, "umas long password", List.of("user-admin"));
        Principal uma = auth.login("uma", "umas long password").principal();

        auth.updateUser(uma, carl.id(), "Carl", true, List.of("configurer"));
    }

    @Test
    public void neverLeavesNobodyToManageUsersOrRoles() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "keeper", "", List.of(Permission.USERS_MANAGE.key(), Permission.ROLES_MANAGE.key()));
        auth.createUser(admin, "kim", null, "kims long password", List.of("keeper"));
        Principal kim = auth.login("kim", "kims long password").principal();
        // alice, the only admin, is disabled by kim: kim's role is now all that manages users and roles
        auth.updateUser(kim, admin.userId(), "Alice", false, List.of("admin"));

        Assert.assertEquals(status(() -> auth.updateRole(kim, "keeper", "",
                List.of(Permission.ROLES_MANAGE.key()))), 400, "nobody could manage users");
        Assert.assertEquals(status(() -> auth.updateRole(kim, "keeper", "",
                List.of(Permission.USERS_MANAGE.key()))), 400, "nobody could manage roles");
    }

    // ---------------- viewing ----------------

    @Test
    public void viewersSeeUsersAndRolesButChangeNothing() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "auditor", "", List.of(Permission.USERS_VIEW.key(), Permission.ROLES_VIEW.key()));
        auth.createUser(admin, "vera", null, "veras long password", List.of("auditor"));
        Principal vera = auth.login("vera", "veras long password").principal();

        Assert.assertEquals(auth.listUsers(vera).size(), 2);
        Assert.assertFalse(auth.listRoles(vera).isEmpty());
        Assert.assertEquals(status(() -> auth.createUser(vera, "x", null, "long enough pass", List.of("auditor"))),
                403);
        Assert.assertEquals(status(() -> auth.createRole(vera, "x", "", List.of(Permission.USERS_VIEW.key()))),
                403);
    }

    // Modifying something includes seeing it, so roles from before view permissions existed keep working
    @Test
    public void managingIncludesViewing() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "user-admin", "", List.of(Permission.USERS_MANAGE.key()));
        auth.createUser(admin, "uma", null, "umas long password", List.of("user-admin"));
        Principal uma = auth.login("uma", "umas long password").principal();

        Assert.assertTrue(uma.can(Permission.USERS_VIEW));
        Assert.assertFalse(uma.can(Permission.ROLES_VIEW));
        Assert.assertEquals(auth.listUsers(uma).size(), 2);
        Assert.assertFalse(auth.listRoles(uma).isEmpty(), "those who assign roles see them");
    }

    @Test
    public void aViewerCannotSeeWhatTheyWereNotGiven() {
        Principal admin = firstAdmin().principal();
        auth.createRole(admin, "people", "", List.of(Permission.USERS_VIEW.key()));
        auth.createUser(admin, "pia", null, "pias long password", List.of("people"));
        Principal pia = auth.login("pia", "pias long password").principal();
        Assert.assertEquals(status(() -> auth.listRoles(pia)), 403);
    }

    @Test
    public void theSchemaCanRunAgain() throws Exception {
        firstAdmin();
        database.migrateAgain();
        Assert.assertFalse(auth.isSetupRequired(), "migrating again keeps the data");
        Optional<UserRecord> alice = database.store().findUserByUsername("alice");
        Assert.assertTrue(alice.isPresent());
        Assert.assertTrue(alice.get().passwordHash().startsWith("pbkdf2-sha256$1000$"));
    }

    private static int status(Runnable action) {
        return Assert.expectThrows(AuthException.class, action::run).status();
    }
}
