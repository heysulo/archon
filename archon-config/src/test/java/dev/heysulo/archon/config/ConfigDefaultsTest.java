package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.sdk.logs.LogSettings;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Clock;

public class ConfigDefaultsTest {
    private TestStore database;
    private ConfigService service;

    @BeforeMethod
    public void setUp() throws Exception {
        database = new TestStore();
        service = new ConfigService(database.store(), Clock.systemUTC());
    }

    @AfterMethod
    public void tearDown() {
        database.close();
    }

    private ConfigEntry global(String name) {
        return service.listAll().stream()
                .filter(entry -> entry.level() == ConfigLevel.GLOBAL && entry.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    public void createsTheGlobalLogSettingsOnce() {
        ConfigDefaults.ensure(service);
        ConfigDefaults.ensure(service);
        Assert.assertEquals(global(LogSettings.LINES).value(), "1000");
        Assert.assertEquals(global(LogSettings.LEVEL).value(), "WARN");
        Assert.assertTrue(global(LogSettings.LEVEL).readOnly(), "applications cannot change it; the portal can");
        Assert.assertEquals(service.listAll().size(), 2);
    }

    @Test
    public void keepsValuesSomeoneChanged() {
        ConfigDefaults.ensure(service);
        ConfigEntry lines = global(LogSettings.LINES);
        service.update(lines.withValue("250"), "admin");
        ConfigDefaults.ensure(service);
        Assert.assertEquals(global(LogSettings.LINES).value(), "250");
    }
}
