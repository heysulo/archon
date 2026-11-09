package dev.heysulo.archon.portal;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;
import dev.heysulo.archon.dictionary.sdk.services.ProcessInfo;
import dev.heysulo.archon.portal.auth.AuthService;
import dev.heysulo.archon.portal.auth.PasswordHasher;
import dev.heysulo.archon.portal.auth.Permission;
import dev.heysulo.archon.portal.applications.ApplicationStore;
import dev.heysulo.archon.portal.auth.TestDatabase;
import dev.heysulo.archon.portal.services.ServiceStore;
import dev.heysulo.archon.sdk.config.ConfigAdmin;
import dev.heysulo.archon.sdk.config.ConfigReplica;
import dev.heysulo.archon.sdk.config.ConfigurationException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class PortalServerTest {

    private static final String ADMIN_PASSWORD = "admins long password";
    private static final ProcessInfo LEADER =
            new ProcessInfo("Group", "Application", 1, "RUNNING", true, true, List.of("ping@10.0.0.5:9001"),
                    new InstanceDetails("app-7d9f", "10.0.0.5", "node-a"));
    private static final ProcessInfo STOPPED =
            new ProcessInfo("Group", "Application", 2, "KILLED", false, false, List.of(), null);

    private final HttpClient http = HttpClient.newHttpClient();
    private final PortalIdentity identity = new PortalIdentity(rank -> true);
    private final FakeFeed feed = new FakeFeed();
    private final FakeConfigs configs = new FakeConfigs();
    private TestDatabase database;
    private PortalServer server;
    private int port;
    private String adminCookie;

    @BeforeClass
    public void start() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        database = new TestDatabase();
        database.addRole("viewer", Permission.PROCESSES_VIEW.key(), Permission.CONFIGS_VIEW.key(),
                Permission.SERVICES_VIEW.key());
        // The registries own this table; the portal only edits its rows
        database.execute("CREATE TABLE services (id VARCHAR(128) PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                + "port_offset INT NOT NULL, app_group VARCHAR(128) NOT NULL, app_name VARCHAR(128) NOT NULL, "
                + "mode VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT uq_services_app_port UNIQUE (app_group, app_name, port_offset))");
        database.execute("CREATE TABLE application_definitions (app_group VARCHAR(128) NOT NULL, "
                + "app_name VARCHAR(128) NOT NULL, description VARCHAR(255) NOT NULL DEFAULT '', "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (app_group, app_name))");
        ApplicationStore applications = new ApplicationStore(database.dataSource());
        AuthService auth = new AuthService(database.store(), new PasswordHasher(1_000), Clock.systemUTC());
        auth.completeSetup(auth.createSetupToken().orElseThrow(), "admin", "Admin", ADMIN_PASSWORD);
        server = new PortalServer(feed, snapshot -> snapshot.servedBy(identity), "/portal-test", new AuthApi(auth),
                new ConfigApi(configs, auth::authenticate),
                new ServicesApi(new ServiceStore(database.dataSource(), applications), feed, auth::authenticate),
                new ApplicationsApi(applications, feed, auth::authenticate), auth::authenticate);
        server.start(port);
        adminCookie = signIn("admin", ADMIN_PASSWORD);
    }

    @AfterClass
    public void stop() {
        server.stop();
        database.close();
    }

    @BeforeMethod
    public void registryUp() throws java.sql.SQLException {
        configs.clear();
        database.execute("DELETE FROM services");
        database.execute("DELETE FROM application_definitions");
        for (String application : List.of("Group:Application", "Shop:Search", "Library:Catalogue")) {
            String[] parts = application.split(":");
            database.execute("INSERT INTO application_definitions (app_group, app_name) VALUES ('" + parts[0]
                    + "', '" + parts[1] + "')");
        }
        database.execute("INSERT INTO services (id, name, port_offset, app_group, app_name, mode) VALUES "
                + "('archon-config', 'Archon configuration', 0, 'Archon', 'Config', 'PRIMARY_MIRROR')");
        configs.failure = null;
        configs.synced = true;
        feed.snapshot = ProcessSnapshot.of("10.21.3.202", 3, List.of(LEADER, STOPPED));
    }

    // ---------------- signing in ----------------

    @Test
    public void keepsDataBehindSignIn() throws Exception {
        Assert.assertEquals(get("/api/processes", null).statusCode(), 401);
        Assert.assertEquals(get("/api/events", null).statusCode(), 401);
        Assert.assertEquals(get("/api/users", null).statusCode(), 401);
        Assert.assertEquals(get("/api/processes", "archon_session=forged").statusCode(), 401);
    }

    @Test
    public void setsASafeSessionCookie() throws Exception {
        HttpResponse<String> response = post("/api/auth/login", "{\"username\":\"admin\",\"password\":\""
                + ADMIN_PASSWORD + "\"}", null, true);
        Assert.assertEquals(response.statusCode(), 200);
        String cookie = response.headers().firstValue("Set-Cookie").orElse("");
        Assert.assertTrue(cookie.startsWith("archon_session="), cookie);
        Assert.assertTrue(cookie.contains("HttpOnly"), cookie);
        Assert.assertTrue(cookie.contains("SameSite=Strict"), cookie);
        Assert.assertTrue(response.body().contains("\"username\":\"admin\""), response.body());
        Assert.assertFalse(response.body().contains("pbkdf2"), "never the password hash");
    }

    @Test
    public void reportsTheSignInState() throws Exception {
        Assert.assertEquals(get("/api/auth/state", null).body(), "{\"setupRequired\":false}");
        HttpResponse<String> signedIn = get("/api/auth/state", adminCookie);
        Assert.assertTrue(signedIn.body().contains("\"permissions\":[\"*\"]"), signedIn.body());
    }

    @Test
    public void rejectsWrongPasswords() throws Exception {
        HttpResponse<String> response = post("/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"wrong password\"}", null, true);
        Assert.assertEquals(response.statusCode(), 401);
        Assert.assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
    }

    // A page on another site can send a form to the portal, but cannot add the portal's header or its origin
    @Test
    public void refusesRequestsFromOtherSites() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}";
        Assert.assertEquals(post("/api/auth/login", body, null, false).statusCode(), 403);
        HttpRequest crossSite = HttpRequest.newBuilder(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json").header(Http.REQUEST_HEADER, "1")
                .header("Origin", "http://evil.example").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        Assert.assertEquals(http.send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode(), 403);
    }

    @Test
    public void signingOutEndsTheSession() throws Exception {
        String cookie = signIn("admin", ADMIN_PASSWORD);
        Assert.assertEquals(post("/api/auth/logout", "{}", cookie, true).statusCode(), 204);
        Assert.assertEquals(get("/api/processes", cookie).statusCode(), 401);
    }

    // ---------------- managing users ----------------

    @Test
    public void managesUsersThroughTheApi() throws Exception {
        HttpResponse<String> created = post("/api/users", "{\"username\":\"dana\",\"displayName\":\"Dana\","
                + "\"password\":\"danas long password\",\"roles\":[\"viewer\"]}", adminCookie, true);
        Assert.assertEquals(created.statusCode(), 201, created.body());
        long id = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        Assert.assertTrue(get("/api/users", adminCookie).body().contains("\"username\":\"dana\""));

        String dana = signIn("dana", "danas long password");
        Assert.assertEquals(get("/api/processes", dana).statusCode(), 200, "viewers see processes");
        Assert.assertEquals(get("/api/users", dana).statusCode(), 403, "but cannot manage users");

        HttpResponse<String> updated = send("PUT", "/api/users/" + id, "{\"displayName\":\"Dana S\","
                + "\"enabled\":false,\"roles\":[\"viewer\"]}", adminCookie);
        Assert.assertEquals(updated.statusCode(), 200, updated.body());
        Assert.assertEquals(get("/api/processes", dana).statusCode(), 401, "disabled users are signed out");

        Assert.assertEquals(send("DELETE", "/api/users/" + id, null, adminCookie).statusCode(), 204);
        Assert.assertEquals(send("DELETE", "/api/users/" + id, null, adminCookie).statusCode(), 404);
    }

    @Test
    public void listsRolesAndPermissions() throws Exception {
        HttpResponse<String> roles = get("/api/roles", adminCookie);
        Assert.assertEquals(roles.statusCode(), 200);
        Assert.assertTrue(roles.body().contains("\"id\":\"admin\""), roles.body());
        Assert.assertTrue(roles.body().contains("\"key\":\"users:manage\""), roles.body());
    }

    @Test
    public void explainsRefusals() throws Exception {
        HttpResponse<String> response = post("/api/users", "{\"username\":\"x\",\"password\":\"short\","
                + "\"roles\":[\"admin\"]}", adminCookie, true);
        Assert.assertEquals(response.statusCode(), 400);
        Assert.assertTrue(response.body().contains("\"error\""), response.body());
    }

    // ---------------- configurations ----------------

    @Test
    public void managesConfigurationsAsTheSignedInUser() throws Exception {
        String body = "{\"level\":\"group\",\"group\":\"Shop\",\"name\":\"ping.intervalMillis\","
                + "\"type\":\"INTEGER\",\"value\":\"500\",\"readOnly\":true}";
        HttpResponse<String> created = post("/api/configs", body, adminCookie, true);
        Assert.assertEquals(created.statusCode(), 201, created.body());
        Assert.assertEquals(configs.lastActor, "admin");
        ConfigEntry stored = configs.replica.entries().iterator().next();
        Assert.assertEquals(stored.level(), ConfigLevel.GROUP);
        Assert.assertTrue(stored.readOnly());
        Assert.assertTrue(get("/api/configs", adminCookie).body().contains("\"name\":\"ping.intervalMillis\""));

        long id = stored.id();
        HttpResponse<String> updated = send("PUT", "/api/configs/" + id, body.replace("500", "750"), adminCookie);
        Assert.assertEquals(updated.statusCode(), 200, updated.body());
        Assert.assertEquals(configs.replica.get(id).orElseThrow().value(), "750");
        Assert.assertEquals(send("DELETE", "/api/configs/" + id, null, adminCookie).statusCode(), 204);
        Assert.assertEquals(configs.replica.size(), 0);
    }

    @Test
    public void letsViewersSeeButNotChangeConfigurations() throws Exception {
        Assert.assertEquals(post("/api/users", "{\"username\":\"vic\",\"displayName\":\"Vic\","
                + "\"password\":\"vics long password\",\"roles\":[\"viewer\"]}", adminCookie, true).statusCode(),
                201);
        String vic = signIn("vic", "vics long password");
        Assert.assertEquals(get("/api/configs", vic).statusCode(), 200);
        Assert.assertEquals(post("/api/configs", "{}", vic, true).statusCode(), 403);
        Assert.assertEquals(get("/api/configs", null).statusCode(), 401);
    }

    @Test
    public void refusesConfigurationChangesFromOtherSites() throws Exception {
        Assert.assertEquals(post("/api/configs", "{\"level\":\"GLOBAL\",\"name\":\"x\",\"type\":\"STRING\"}",
                adminCookie, false).statusCode(), 403);
        Assert.assertEquals(configs.replica.size(), 0);
    }

    @Test
    public void reportsAnUnreachableConfigServerAs503AndRefusalsAs400() throws Exception {
        String body = "{\"level\":\"GLOBAL\",\"name\":\"x\",\"type\":\"STRING\",\"value\":\"\"}";
        configs.failure = ConfigurationException.unavailable("archon-config is not reachable", null);
        Assert.assertEquals(post("/api/configs", body, adminCookie, true).statusCode(), 503);
        configs.failure = new ConfigurationException("Value is not a whole number");
        HttpResponse<String> refused = post("/api/configs", body, adminCookie, true);
        Assert.assertEquals(refused.statusCode(), 400);
        Assert.assertTrue(refused.body().contains("not a whole number"), refused.body());
        configs.failure = null;
        configs.synced = false;
        Assert.assertEquals(get("/api/configs", adminCookie).statusCode(), 503, "still loading");
        configs.synced = true;
        Assert.assertEquals(post("/api/configs", "{\"level\":\"nowhere\"}", adminCookie, true).statusCode(), 400);
    }

    @Test
    public void servesConfigurationsOnePageAtATime() throws Exception {
        configs.add(120);
        HttpResponse<String> first = get("/api/configs?limit=50", adminCookie);
        Assert.assertEquals(first.statusCode(), 200, first.body());
        Assert.assertTrue(first.body().contains("\"total\":120"), first.body());
        Assert.assertTrue(first.body().contains("\"name\":\"name.00000\""));
        Assert.assertFalse(first.body().contains("\"name\":\"name.00050\""));

        HttpResponse<String> third = get("/api/configs?offset=100&limit=50", adminCookie);
        Assert.assertTrue(third.body().contains("\"name\":\"name.00119\""), third.body());
        Assert.assertFalse(third.body().contains("\"name\":\"name.00099\""));

        HttpResponse<String> searched = get("/api/configs?q=NAME.0011&level=global", adminCookie);
        Assert.assertTrue(searched.body().contains("\"total\":10"), searched.body());
        Assert.assertTrue(get("/api/configs?level=GROUP", adminCookie).body().contains("\"total\":0"));
        Assert.assertEquals(get("/api/configs?limit=lots", adminCookie).statusCode(), 400);
        Assert.assertTrue(get("/api/configs?limit=100000", adminCookie).body().contains("\"limit\":200"),
                "pages are capped");
    }

    // ---------------- services ----------------

    @Test
    public void managesServices() throws Exception {
        String reports = "{\"id\":\" reports \",\"name\":\"Reports\",\"portOffset\":5,\"group\":\"Group\","
                + "\"application\":\"Application\",\"mode\":\"PRIMARY_MIRROR\"}";
        HttpResponse<String> created = post("/api/services", reports, adminCookie, true);
        Assert.assertEquals(created.statusCode(), 201, created.body());
        Assert.assertTrue(created.body().contains("\"id\":\"reports\""), "trimmed: " + created.body());
        Assert.assertEquals(post("/api/services", reports, adminCookie, true).statusCode(), 409, "same id");

        HttpResponse<String> listed = get("/api/services", adminCookie);
        Assert.assertTrue(listed.body().contains("\"id\":\"archon-config\""), listed.body());
        Assert.assertTrue(listed.body().contains("\"managedByArchon\":true"), listed.body());

        HttpResponse<String> updated = send("PUT", "/api/services/reports", reports.replace("\"portOffset\":5",
                "\"portOffset\":4").replace("PRIMARY_MIRROR", "LOAD_BALANCE"), adminCookie);
        Assert.assertEquals(updated.statusCode(), 200, updated.body());
        Assert.assertTrue(updated.body().contains("\"portOffset\":4"), updated.body());
        Assert.assertTrue(updated.body().contains("\"mode\":\"LOAD_BALANCE\""), updated.body());

        Assert.assertEquals(send("DELETE", "/api/services/reports", null, adminCookie).statusCode(), 204);
        Assert.assertEquals(send("DELETE", "/api/services/reports", null, adminCookie).statusCode(), 404);
    }

    @Test
    public void refusesInvalidAndClashingServices() throws Exception {
        String base = "{\"id\":\"search\",\"name\":\"Search\",\"portOffset\":2,\"group\":\"Shop\","
                + "\"application\":\"Search\",\"mode\":\"LOAD_BALANCE\"}";
        Assert.assertEquals(post("/api/services", base, adminCookie, true).statusCode(), 201);
        // Another application entirely: offsets are unique across every service
        HttpResponse<String> clash = post("/api/services", base.replace("\"search\"", "\"index\"")
                .replace("\"Shop\"", "\"Library\"").replace("\"application\":\"Search\"",
                        "\"application\":\"Catalogue\""), adminCookie, true);
        Assert.assertEquals(clash.statusCode(), 409, "same offset, other application");
        Assert.assertTrue(clash.body().contains("Port offset 2 is taken by search"), clash.body());
        Assert.assertEquals(post("/api/services", base.replace("\"search\"", "\"archon-clash\"")
                .replace("\"portOffset\":2", "\"portOffset\":0"), adminCookie, true).statusCode(), 409,
                "taken by archon-config");
        Assert.assertEquals(post("/api/services", base.replace("search\",\"name", "has space\",\"name"),
                adminCookie, true).statusCode(), 400);
        Assert.assertEquals(post("/api/services", base.replace("\"portOffset\":2", "\"portOffset\":70000"),
                adminCookie, true).statusCode(), 400);
        Assert.assertEquals(post("/api/services", base.replace("LOAD_BALANCE", "SOMETIMES"), adminCookie, true)
                .statusCode(), 400);
        Assert.assertEquals(post("/api/services", base.replace(",\"portOffset\":2", ""), adminCookie, true)
                .statusCode(), 400);
    }

    // LEADER serves ping. Changing it would leave the instance on the old port or owner.
    @Test
    public void refusesChangesToRunningServices() throws Exception {
        String ping = "{\"id\":\"ping\",\"name\":\"Ping\",\"portOffset\":1,\"group\":\"Group\","
                + "\"application\":\"Application\",\"mode\":\"PRIMARY_MIRROR\"}";
        Assert.assertEquals(post("/api/services", ping, adminCookie, true).statusCode(), 201, "adding is fine");

        HttpResponse<String> changed = send("PUT", "/api/services/ping", ping.replace("\"portOffset\":1",
                "\"portOffset\":2"), adminCookie);
        Assert.assertEquals(changed.statusCode(), 409, changed.body());
        Assert.assertTrue(changed.body().contains("running on Group / Application #1"), changed.body());
        Assert.assertEquals(send("DELETE", "/api/services/ping", null, adminCookie).statusCode(), 409);

        // Nobody can say whether it runs while no registry answers
        feed.snapshot = null;
        Assert.assertEquals(send("DELETE", "/api/services/ping", null, adminCookie).statusCode(), 503);

        feed.snapshot = ProcessSnapshot.of("10.21.3.202", 3, List.of(STOPPED));
        Assert.assertEquals(send("DELETE", "/api/services/ping", null, adminCookie).statusCode(), 204,
                "stopped, so it can go");
    }

    // Removing archon-config would stop every application receiving configurations
    @Test
    public void refusesChangesToArchonsOwnServices() throws Exception {
        String archon = "{\"id\":\"archon-config\",\"name\":\"Archon configuration\",\"portOffset\":7,"
                + "\"group\":\"Archon\",\"application\":\"Config\",\"mode\":\"PRIMARY_MIRROR\"}";
        Assert.assertEquals(send("PUT", "/api/services/archon-config", archon, adminCookie).statusCode(), 400);
        Assert.assertEquals(send("DELETE", "/api/services/archon-config", null, adminCookie).statusCode(), 400);
        Assert.assertEquals(post("/api/services", archon.replace("archon-config", "archon-extra"), adminCookie,
                true).statusCode(), 400);
        Assert.assertTrue(get("/api/services", adminCookie).body().contains("\"portOffset\":0"));
    }

    @Test
    public void letsViewersSeeButNotChangeServices() throws Exception {
        Assert.assertEquals(post("/api/users", "{\"username\":\"sam\",\"displayName\":\"Sam\","
                + "\"password\":\"sams long password\",\"roles\":[\"viewer\"]}", adminCookie, true).statusCode(),
                201);
        String sam = signIn("sam", "sams long password");
        Assert.assertEquals(get("/api/services", sam).statusCode(), 200);
        Assert.assertEquals(send("DELETE", "/api/services/anything", null, sam).statusCode(), 403);
        Assert.assertEquals(get("/api/services", null).statusCode(), 401);
        Assert.assertEquals(post("/api/services", "{}", adminCookie, false).statusCode(), 403, "other sites");
    }

    @Test
    public void refusesServicesOfUndefinedApplications() throws Exception {
        HttpResponse<String> refused = post("/api/services", "{\"id\":\"orphan\",\"name\":\"Orphan\","
                + "\"portOffset\":30,\"group\":\"Nowhere\",\"application\":\"Nobody\",\"mode\":\"LOAD_BALANCE\"}",
                adminCookie, true);
        Assert.assertEquals(refused.statusCode(), 400);
        Assert.assertTrue(refused.body().contains("not a defined application"), refused.body());
    }

    @Test
    public void givesAServiceToOneApplication() throws Exception {
        Assert.assertEquals(post("/api/services", "{\"id\":\"search\",\"name\":\"Search\",\"portOffset\":12,"
                + "\"group\":\"Shop\",\"application\":\"Search\",\"mode\":\"LOAD_BALANCE\"}", adminCookie,
                true).statusCode(), 201);

        HttpResponse<String> moved = send("PUT", "/api/services/search/owner",
                "{\"group\":\"Library\",\"application\":\"Catalogue\"}", adminCookie);
        Assert.assertEquals(moved.statusCode(), 200, moved.body());
        Assert.assertTrue(moved.body().contains("\"group\":\"Library\",\"application\":\"Catalogue\""),
                moved.body());
        Assert.assertTrue(moved.body().contains("\"portOffset\":12"), "the rest is kept: " + moved.body());
        // The one owner it had is gone, so its definition can now be removed
        Assert.assertEquals(send("DELETE", "/api/applications/Shop/Search", null, adminCookie).statusCode(), 204);
    }

    @Test
    public void refusesGivingAServiceAwayWrongly() throws Exception {
        Assert.assertEquals(send("PUT", "/api/services/ping/owner",
                "{\"group\":\"Shop\",\"application\":\"Search\"}", adminCookie).statusCode(), 409, "running");
        Assert.assertEquals(post("/api/services", "{\"id\":\"search\",\"name\":\"Search\",\"portOffset\":12,"
                + "\"group\":\"Shop\",\"application\":\"Search\",\"mode\":\"LOAD_BALANCE\"}", adminCookie,
                true).statusCode(), 201);
        HttpResponse<String> undefined = send("PUT", "/api/services/search/owner",
                "{\"group\":\"Nowhere\",\"application\":\"Nobody\"}", adminCookie);
        Assert.assertEquals(undefined.statusCode(), 400, undefined.body());
        Assert.assertEquals(send("PUT", "/api/services/search/owner",
                "{\"group\":\"Archon\",\"application\":\"Config\"}", adminCookie).statusCode(), 400, "Archon");
        Assert.assertEquals(send("PUT", "/api/services/missing/owner",
                "{\"group\":\"Shop\",\"application\":\"Search\"}", adminCookie).statusCode(), 404);
        Assert.assertEquals(send("GET", "/api/services/search/owner", null, adminCookie).statusCode(), 405);
    }

    // ---------------- application definitions ----------------

    @Test
    public void definesApplications() throws Exception {
        HttpResponse<String> created = post("/api/applications", "{\"group\":\" Shop \",\"name\":\"Cart\","
                + "\"description\":\"Baskets\"}", adminCookie, true);
        Assert.assertEquals(created.statusCode(), 201, created.body());
        Assert.assertEquals(post("/api/applications", "{\"group\":\"Shop\",\"name\":\"Cart\"}", adminCookie,
                true).statusCode(), 409, "already defined");

        HttpResponse<String> listed = get("/api/applications", adminCookie);
        Assert.assertTrue(listed.body().contains("{\"group\":\"Archon\",\"name\":\"Portal\""), listed.body());
        Assert.assertTrue(listed.body().contains("\"name\":\"Cart\",\"description\":\"Baskets\""), listed.body());

        HttpResponse<String> updated = send("PUT", "/api/applications/Shop/Cart", "{\"description\":\"Carts\"}",
                adminCookie);
        Assert.assertEquals(updated.statusCode(), 200, updated.body());
        Assert.assertTrue(updated.body().contains("\"description\":\"Carts\""), updated.body());

        Assert.assertEquals(send("DELETE", "/api/applications/Shop/Cart", null, adminCookie).statusCode(), 204);
        Assert.assertEquals(send("DELETE", "/api/applications/Shop/Cart", null, adminCookie).statusCode(), 404);
    }

    @Test
    public void refusesInvalidDefinitions() throws Exception {
        Assert.assertEquals(post("/api/applications", "{\"group\":\"Archon\",\"name\":\"Extra\"}",
                adminCookie, true).statusCode(), 400, "Archon's group");
        Assert.assertEquals(post("/api/applications", "{\"group\":\"Has Space\",\"name\":\"X\"}",
                adminCookie, true).statusCode(), 400);
        Assert.assertEquals(send("DELETE", "/api/applications/Archon/Portal", null, adminCookie).statusCode(), 400);
    }

    // LEADER runs as Group / Application: removing its definition would refuse it on its next reconnect
    @Test
    public void keepsTheDefinitionsOfRunningApplications() throws Exception {
        HttpResponse<String> refused = send("DELETE", "/api/applications/Group/Application", null, adminCookie);
        Assert.assertEquals(refused.statusCode(), 409, refused.body());
        Assert.assertTrue(refused.body().contains("1 instance running"), refused.body());
    }

    @Test
    public void keepsTheDefinitionsOfApplicationsOwningServices() throws Exception {
        Assert.assertEquals(post("/api/services", "{\"id\":\"search\",\"name\":\"Search\",\"portOffset\":12,"
                + "\"group\":\"Shop\",\"application\":\"Search\",\"mode\":\"LOAD_BALANCE\"}", adminCookie,
                true).statusCode(), 201);
        HttpResponse<String> refused = send("DELETE", "/api/applications/Shop/Search", null, adminCookie);
        Assert.assertEquals(refused.statusCode(), 409, refused.body());
        Assert.assertTrue(refused.body().contains("owns search"), refused.body());
    }

    // ---------------- processes ----------------

    @Test
    public void servesTheLatestProcessesAsJson() throws Exception {
        HttpResponse<String> response = get("/api/processes", adminCookie);
        Assert.assertEquals(response.statusCode(), 200);
        Assert.assertTrue(response.body().contains("\"registry\":\"10.21.3.202\""), response.body());
        Assert.assertTrue(response.body().contains("\"servedBy\":{\"host\""), response.body());
        Assert.assertTrue(response.body().contains("\"details\":{\"host\":\"app-7d9f\""), response.body());
    }

    @Test
    public void reportsAnUnreachableRegistryAs503() throws Exception {
        feed.snapshot = null;
        Assert.assertEquals(get("/api/processes", adminCookie).statusCode(), 503);
    }

    @Test
    public void streamsTheListAtOnceThenEveryChange() throws Exception {
        try (EventStream events = openEvents(adminCookie)) {
            Assert.assertEquals(events.nextEvent(), "snapshot");
            Assert.assertTrue(events.lastData.contains("10.21.3.202"), events.lastData);
            feed.push(ProcessSnapshot.of("10.21.3.203", 4, List.of(LEADER)));
            Assert.assertEquals(events.nextEvent(), "snapshot");
            Assert.assertTrue(events.lastData.contains("\"term\":4"), events.lastData);
        }
    }

    @Test
    public void streamsAnOfflineEventWhenTheRegistryIsLost() throws Exception {
        try (EventStream events = openEvents(adminCookie)) {
            Assert.assertEquals(events.nextEvent(), "snapshot");
            feed.lose("Lost the registry at 10.21.3.202");
            Assert.assertEquals(events.nextEvent(), "offline");
        }
    }

    // ---------------- frontend ----------------

    @Test
    public void servesTheAppForEveryRouteWithoutSignIn() throws Exception {
        for (String route : List.of("/", "/processes", "/login", "/users")) {
            HttpResponse<String> response = get(route, null);
            Assert.assertEquals(response.statusCode(), 200, route);
            Assert.assertTrue(response.body().contains("<title>Archon Portal</title>"), route);
        }
        Assert.assertEquals(get("/healthz", null).statusCode(), 200);
    }

    @Test
    public void servesAssetsWithLongCaching() throws Exception {
        HttpResponse<String> script = get("/assets/app-1a2b3c.js", null);
        Assert.assertEquals(script.statusCode(), 200);
        Assert.assertTrue(script.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
        Assert.assertEquals(get("/assets/gone-9f8e7d.js", null).statusCode(), 404);
        Assert.assertEquals(get("/api/nothing", adminCookie).statusCode(), 404);
    }

    @Test
    public void refusesPathsLeavingTheBuildDirectory() throws Exception {
        HttpResponse<String> response = get("/assets/%2e%2e/%2e%2e/dev/heysulo/archon/portal/PortalServer.class",
                null);
        Assert.assertEquals(response.statusCode(), 404);
    }

    @Test
    public void setsSecurityHeaders() throws Exception {
        HttpResponse<String> response = get("/", null);
        Assert.assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("")
                .contains("default-src 'self'"));
        Assert.assertEquals(response.headers().firstValue("X-Frame-Options").orElse(""), "DENY");
    }

    @Test
    public void rejectsAnInvalidPort() {
        Assert.assertEquals(PortalApplication.port(null), PortalApplication.DEFAULT_PORT);
        Assert.assertEquals(PortalApplication.port("9090"), 9090);
        Assert.expectThrows(IllegalArgumentException.class, () -> PortalApplication.port("http"));
    }

    // ---------------- helpers ----------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String signIn(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\""
                + password + "\"}", null, true);
        Assert.assertEquals(response.statusCode(), 200, response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String cookie, boolean portalHeader)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (portalHeader) {
            request.header(Http.REQUEST_HEADER, "1");
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                .header(Http.REQUEST_HEADER, "1").header("Cookie", cookie)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private EventStream openEvents(String cookie) throws Exception {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url("/api/events")))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofInputStream());
        Assert.assertEquals(response.statusCode(), 200);
        EventStream stream = new EventStream(response.body());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        stream.peek();
        while (server.openStreams() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return stream;
    }

    /** Reads Server-Sent Events: blocks of "event:" and "data:" lines separated by blank lines. */
    private static final class EventStream implements AutoCloseable {
        private final InputStream body;
        private final BufferedReader reader;
        private final List<String> buffered = new ArrayList<>();
        private String lastData;

        EventStream(InputStream body) {
            this.body = body;
            this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        void peek() throws IOException {
            String line = reader.readLine();
            if (line != null) {
                buffered.add(line);
            }
        }

        String nextEvent() throws IOException {
            String event = null;
            while (true) {
                String line = buffered.isEmpty() ? reader.readLine() : buffered.remove(0);
                Assert.assertNotNull(line, "stream ended");
                if (line.startsWith("event: ")) {
                    event = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    lastData = line.substring(6);
                } else if (line.isEmpty() && event != null) {
                    return event;
                }
            }
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static final class FakeFeed implements ProcessFeed {
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private volatile ProcessSnapshot snapshot;

        @Override
        public ProcessSnapshot current() {
            ProcessSnapshot current = snapshot;
            if (current == null) {
                throw new PortalException("No primary registry is answering");
            }
            return current;
        }

        @Override
        public void subscribe(Listener listener) {
            listeners.add(listener);
        }

        void push(ProcessSnapshot next) {
            snapshot = next;
            listeners.forEach(listener -> listener.onSnapshot(next));
        }

        void lose(String reason) {
            snapshot = null;
            listeners.forEach(listener -> listener.onLost(reason));
        }
    }

    /** archon-config, in memory: each change is pushed back into the portal's replica, as the primary does. */
    private static final class FakeConfigs implements ConfigApi.Source, ConfigAdmin {
        private final ConfigReplica replica = new ConfigReplica();
        private volatile ConfigurationException failure;
        private volatile String lastActor;
        private volatile boolean synced = true;
        private long nextId = 1;
        private long revision;

        @Override
        public ConfigReplica replica() {
            return replica;
        }

        @Override
        public boolean synced() {
            return synced;
        }

        @Override
        public ConfigAdmin admin() {
            check();
            return this;
        }

        @Override
        public synchronized ConfigEntry create(ConfigEntry entry, String actor) {
            ConfigEntry stored = new ConfigEntry(nextId++, entry.level(), entry.group(), entry.application(),
                    entry.name(), entry.type(), entry.value(), entry.readOnly(), entry.description(), "now", actor);
            return push(stored, false, actor);
        }

        @Override
        public synchronized ConfigEntry update(ConfigEntry entry, String actor) {
            ConfigEntry current = replica.get(entry.id()).orElseThrow();
            return push(new ConfigEntry(current.id(), current.level(), current.group(), current.application(),
                    current.name(), entry.type(), entry.value(), entry.readOnly(), entry.description(), "now", actor),
                    false, actor);
        }

        @Override
        public synchronized void delete(long id, String actor) {
            push(replica.get(id).orElseThrow(), true, actor);
        }

        synchronized void add(int count) {
            for (int i = 0; i < count; i++) {
                create(new ConfigEntry(0, ConfigLevel.GLOBAL, "", "", String.format("name.%05d", i), ConfigType.INTEGER,
                        String.valueOf(i), false, "", null, null), "seed");
            }
        }

        void clear() {
            replica.replace(List.of(), 0);
            revision = 0;
        }

        private ConfigEntry push(ConfigEntry entry, boolean deleted, String actor) {
            lastActor = actor;
            revision++;
            replica.apply(List.of(new ConfigRow(entry, deleted, revision)), revision);
            return entry;
        }

        private void check() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
