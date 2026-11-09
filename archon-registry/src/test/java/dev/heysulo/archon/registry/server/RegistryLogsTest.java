package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.dictionary.sdk.messages.LogBatch;
import dev.heysulo.archon.dictionary.sdk.messages.LogStreamControl;
import dev.heysulo.archon.dictionary.sdk.messages.ProcessLogBatch;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.db.Database;
import dev.heysulo.archon.sdk.logs.LogSettings;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

/** A registry's own logs: the primary relays its own, and a mirror sends its to the primary. */
public class RegistryLogsTest {

    @AfterMethod
    public void reset() {
        RegistryLogs.getInstance().stop();
        LogRelays.getInstance().clear();
        RegistryServer.setRank(RANK_PRIMARY);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Assert.assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    public void thePrimaryRelaysItsOwnLines() throws Exception {
        RegistryServer.setRank(RANK_PRIMARY);
        Application self = RegistryServer.getApplicationRegistry();
        self.setRunLevel(RunLevel.RUNNING);
        FakeConnection watcher = new FakeConnection();

        LogRelays.getInstance().watch(watcher, self);
        waitFor(() -> watcher.last(ProcessLogBatch.class) != null, "the registry's kept lines reach the watcher");
        Assert.assertTrue(watcher.last(ProcessLogBatch.class).isHistory());
        Assert.assertEquals(watcher.last(ProcessLogBatch.class).getName(), "Registry");

        LogRelays.getInstance().unwatch(watcher, self);
        int before = watcher.sent(ProcessLogBatch.class).size();
        Thread.sleep(600);
        Assert.assertEquals(watcher.sent(ProcessLogBatch.class).size(), before, "nothing once nobody watches");
    }

    @Test
    public void aMirrorSendsItsLinesToThePrimary() throws Exception {
        RegistryServer.setRank(2);
        Application primary = new Application("Archon", "Registry", RANK_PRIMARY);
        FakeConnection toPrimary = new FakeConnection();
        primary.setConnection(toPrimary);

        primary.onMessage(toPrimary, new LogStreamControl(true));
        waitFor(() -> toPrimary.last(LogBatch.class) != null, "the mirror sends its kept lines");
        Assert.assertTrue(toPrimary.last(LogBatch.class).isHistory());

        primary.onMessage(toPrimary, new LogStreamControl(false));
        int before = toPrimary.sent(LogBatch.class).size();
        Thread.sleep(600);
        Assert.assertEquals(toPrimary.sent(LogBatch.class).size(), before);
    }

    @Test
    public void followsTheConfigurationsAsArchonRegistry() throws Exception {
        Database database = new Database("jdbc:h2:mem:logs-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORE_UNKNOWN_SETTINGS=TRUE", "sa", "");
        String before = LogSettings.describe();
        RegistryLogs.reloadSettings(database);
        Assert.assertEquals(LogSettings.describe(), before,
                "without a configurations table, or a reachable database, the settings stay as they are");

        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE configurations (level VARCHAR(16), app_group VARCHAR(128), "
                    + "app_name VARCHAR(128), name VARCHAR(128), value_type VARCHAR(16), config_value TEXT, "
                    + "deleted BOOLEAN)");
            statement.execute("INSERT INTO configurations VALUES ('GLOBAL', '', '', 'archon.logs.memoryLevel', "
                    + "'STRING', 'ERROR', FALSE), ('APPLICATION', 'Archon', 'Registry', 'archon.logs.memoryLevel', "
                    + "'STRING', 'INFO', FALSE), ('GROUP', 'Archon', '', 'archon.logs.memoryLines', 'INTEGER', "
                    + "'250', FALSE), ('APPLICATION', 'Shop', 'Cart', 'archon.logs.memoryLines', 'INTEGER', '9', "
                    + "FALSE), ('GLOBAL', '', '', 'archon.logs.memoryLines', 'INTEGER', '5', TRUE)");
        }
        RegistryLogs.reloadSettings(database);
        Assert.assertEquals(LogSettings.describe(), "250 log lines at INFO or above");
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM configurations");
        }
        RegistryLogs.reloadSettings(database);
        Assert.assertEquals(LogSettings.describe(), "1000 log lines at WARN or above");
    }
}
