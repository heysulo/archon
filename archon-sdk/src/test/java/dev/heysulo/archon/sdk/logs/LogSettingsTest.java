package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LogSettingsTest {

    private static ConfigEntry entry(ConfigLevel level, String group, String application, String name, String value) {
        return new ConfigEntry(1, level, group, application, name, ConfigType.STRING, value, false, "", null, null);
    }

    private static LogLine line(String level, String message) {
        return new LogLine(1, level, "", "", message);
    }

    @Test
    public void theMostSpecificConfigurationWins() {
        List<ConfigEntry> entries = List.of(
                entry(ConfigLevel.GLOBAL, "", "", LogSettings.LINES, "1000"),
                entry(ConfigLevel.GROUP, "Shop", "", LogSettings.LINES, "200"),
                entry(ConfigLevel.APPLICATION, "Shop", "Cart", LogSettings.LINES, "50"),
                entry(ConfigLevel.APPLICATION, "Shop", "Search", LogSettings.LINES, "70"));
        Assert.assertEquals(LogSettings.effective(entries, "Shop", "Cart", LogSettings.LINES), Optional.of("50"));
        Assert.assertEquals(LogSettings.effective(entries, "Shop", "Basket", LogSettings.LINES), Optional.of("200"));
        Assert.assertEquals(LogSettings.effective(entries, "Library", "Catalogue", LogSettings.LINES),
                Optional.of("1000"));
        Assert.assertEquals(LogSettings.effective(entries, "Shop", "Cart", LogSettings.LEVEL), Optional.empty());
    }

    @Test
    public void readsValuesForgivingly() {
        Assert.assertEquals(LogSettings.lines(null), LogSettings.DEFAULT_LINES);
        Assert.assertEquals(LogSettings.lines("250"), 250);
        Assert.assertEquals(LogSettings.lines("9999999"), LogSettings.MAX_LINES);
        Assert.assertEquals(LogSettings.lines("-1"), LogSettings.DEFAULT_LINES);
        Assert.assertEquals(LogSettings.lines("lots"), LogSettings.DEFAULT_LINES);
        Assert.assertEquals(LogSettings.level(null), "WARN");
        Assert.assertEquals(LogSettings.level(" info "), "INFO");
        Assert.assertEquals(LogSettings.level("Warning"), "WARN");
        Assert.assertEquals(LogSettings.level("LOUD"), "WARN");
    }

    @Test
    public void keepsOnlyLinesAtOrAboveTheLevelButStreamsThemAll() {
        LogBuffer buffer = LogBuffer.create(10, 10);
        Map<String, String> settings = Map.of(LogSettings.LEVEL, "WARN");
        LogSettings.apply(buffer, name -> Optional.ofNullable(settings.get(name)));
        buffer.add(line("INFO", "routine"));
        buffer.add(line("WARN", "slow"));
        buffer.add(line("STDERR", "Exception in thread main"));
        buffer.add(line("STDOUT", "hello"));
        Assert.assertEquals(buffer.startStreaming().stream().map(LogLine::message).toList(),
                List.of("slow", "Exception in thread main"));
        buffer.add(line("DEBUG", "detail"));
        Assert.assertEquals(buffer.drain(10).lines().size(), 1, "a watcher sees every level");
    }

    @Test
    public void followsChangesToTheSettings() {
        LogBuffer buffer = LogBuffer.create(10, 10);
        LogSettings.apply(buffer, name -> Optional.of(name.equals(LogSettings.LINES) ? "3" : "INFO"));
        for (int i = 0; i < 5; i++) {
            buffer.add(line("INFO", "line " + i));
        }
        Assert.assertEquals(buffer.historySize(), 3);
        LogSettings.apply(buffer, name -> Optional.of(name.equals(LogSettings.LINES) ? "2" : "ERROR"));
        Assert.assertTrue(buffer.startStreaming().isEmpty(), "lines below the new level go at once");
        LogSettings.apply(buffer, name -> Optional.of(name.equals(LogSettings.LINES) ? "0" : "TRACE"));
        buffer.add(line("ERROR", "kept nowhere"));
        Assert.assertTrue(buffer.startStreaming().isEmpty(), "0 keeps none");
    }
}
