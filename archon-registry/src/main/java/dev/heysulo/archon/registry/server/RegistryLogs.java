package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.dictionary.sdk.messages.LogBatch;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.db.Database;
import dev.heysulo.archon.sdk.logs.LogCapture;
import dev.heysulo.archon.sdk.logs.LogSettings;
import dev.heysulo.archon.sdk.logs.LogShipper;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * This registry's own log lines, for the portal's live logs, through the SDK's log capture. Registries are not SDK
 * applications, so the lines go one of two ways: the primary hands its own straight to {@link LogRelays}, and a
 * mirror sends its to the primary over the connection it already holds, as an application does.
 *
 * <p>How many lines are kept follows the same configurations applications use, as {@code Archon:Registry}, read from
 * the database every time the services are.
 */
public final class RegistryLogs {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryLogs.class);
    private static final RegistryLogs INSTANCE = new RegistryLogs();

    // One shipper for the life of the process; only where its batches go changes
    private volatile Consumer<Message> target = message -> { };
    private final LogShipper shipper = new LogShipper(message -> target.accept(message));

    private RegistryLogs() {
    }

    public static RegistryLogs getInstance() {
        return INSTANCE;
    }

    /** Starts capturing, as early as possible so the first watcher sees how the registry started. */
    public static void install() {
        LogCapture.install();
        LogSettings.apply(name -> Optional.empty());
    }

    /** Someone started or stopped watching this registry while it is primary: its lines go to the relay. */
    synchronized void local(Application self, boolean streaming) {
        if (streaming) {
            stream(message -> {
                if (message instanceof LogBatch batch) {
                    LogRelays.getInstance().fromInstance(self, batch);
                }
            });
        } else {
            stop();
        }
    }

    /** The primary asked this mirror for its lines, or to stop: they go over the connection to the primary. */
    public synchronized void toPrimary(Connection primary, boolean streaming) {
        if (streaming) {
            stream(primary::send);
        } else {
            stop();
        }
    }

    /** The primary changed: a new one asks again if anyone watches. */
    public synchronized void stop() {
        if (shipper.isRunning()) {
            shipper.stop();
            LOGGER.info("Stopped streaming this registry's logs");
        }
    }

    private void stream(Consumer<Message> to) {
        shipper.stop();
        target = to;
        shipper.start();
        LOGGER.info("Streaming this registry's logs to the portal");
    }

    /** Reads this registry's log settings from the configurations, if archon-config has created its tables. */
    public static void reloadSettings(Database database) {
        try (java.sql.Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement("SELECT level, app_group, app_name, name, "
                     + "value_type, config_value FROM configurations WHERE deleted = FALSE AND name IN (?, ?)")) {
            select.setString(1, LogSettings.LINES);
            select.setString(2, LogSettings.LEVEL);
            List<ConfigEntry> entries = new ArrayList<>();
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    entries.add(new ConfigEntry(0, ConfigLevel.valueOf(result.getString("level")
                            .toUpperCase(Locale.ROOT)), result.getString("app_group"), result.getString("app_name"),
                            result.getString("name"), ConfigType.valueOf(result.getString("value_type")
                            .toUpperCase(Locale.ROOT)), result.getString("config_value"), true, "", null, null));
                }
            }
            LogSettings.apply(entries, Constants.APPLICATION_GROUP_NAME, Constants.APPLICATION_NAME_REGISTRY);
        } catch (SQLException | IllegalArgumentException e) {
            // No configurations table yet, as before archon-config first starts: the defaults stand
            LOGGER.debug("Cannot read the log settings: {}", e.getMessage());
        }
    }
}
