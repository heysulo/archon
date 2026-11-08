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
