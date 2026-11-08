package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigLevel;
import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;
import dev.heysulo.archon.dictionary.sdk.config.ConfigType;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigChanged;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRows;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigSnapshot;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 100,000 configurations and 500 connected application instances, held in memory as the primary holds them. The
 * database is not involved: rows arrive as a mirror receives them, which runs the same push work as a write.
 *
 * <p>The layout: 50 groups of 10 applications each. Every application has 190 configurations of its own (95,000),
 * every group 90 (4,500) and there are 500 global ones. Each application runs once.
 */
public class ConfigScaleTest {
    private static final int GROUPS = 50;
    private static final int APPLICATIONS_PER_GROUP = 10;
    private static final int PER_APPLICATION = 190;
    private static final int PER_GROUP = 90;
    private static final int GLOBAL = 500;
    // Generous: a slow CI machine must not fail this, but a return to scanning every configuration would
    private static final long LIMIT_MILLIS = 1_000;

    private final ConfigService service = new ConfigService(null, Clock.systemUTC());
    private final AtomicInteger pushes = new AtomicInteger();
    private final AtomicInteger snapshotEntries = new AtomicInteger();
    private final AtomicInteger replicaRows = new AtomicInteger();
    private final AtomicLong nextId = new AtomicLong(1);
    private long revision;

    @BeforeClass
    public void load() {
        List<ConfigRow> rows = new ArrayList<>();
        for (int i = 0; i < GLOBAL; i++) {
            rows.add(row(ConfigLevel.GLOBAL, "", "", "global." + i, "1"));
        }
        for (int g = 0; g < GROUPS; g++) {
            for (int i = 0; i < PER_GROUP; i++) {
                rows.add(row(ConfigLevel.GROUP, "group" + g, "", "group." + i, "1"));
            }
            for (int a = 0; a < APPLICATIONS_PER_GROUP; a++) {
                for (int i = 0; i < PER_APPLICATION; i++) {
                    rows.add(row(ConfigLevel.APPLICATION, "group" + g, "app" + a, "app." + i, "1"));
                }
            }
        }
        long started = System.nanoTime();
        service.replace(rows, revision);
        System.out.printf("Loaded %,d configurations in %d ms%n", service.size(), millisSince(started));
        Assert.assertEquals(service.size(), 100_000);

        started = System.nanoTime();
        for (int g = 0; g < GROUPS; g++) {
            for (int a = 0; a < APPLICATIONS_PER_GROUP; a++) {
                service.subscribe(new ConfigService.Subscriber("group" + g, "app" + a, message -> {
                    if (message instanceof ConfigChanged) {
                        pushes.incrementAndGet();
                    } else if (message instanceof ConfigSnapshot snapshot) {
                        snapshotEntries.addAndGet(snapshot.getEntries().size());
                    }
                }));
            }
        }
        System.out.printf("Subscribed 500 applications in %d ms%n", millisSince(started));
        // Each sees 500 global + 90 group + 190 own
        Assert.assertEquals(snapshotEntries.get(), 500 * (GLOBAL + PER_GROUP + PER_APPLICATION));

        service.subscribeReplica(new ConfigService.Replica(message ->
                replicaRows.addAndGet(((ConfigRows) message).getRows().size())), 0);
        Assert.assertEquals(replicaRows.get(), 100_000);
    }

    @Test
    public void aGlobalChangeReachesEveryApplicationQuickly() {
        assertChange(row(ConfigLevel.GLOBAL, "", "", "global.7", "2", 8), 500);
    }

    @Test
    public void aGroupChangeReachesOnlyThatGroup() {
        assertChange(row(ConfigLevel.GROUP, "group3", "", "group.5", "2", GLOBAL + 3 * (PER_GROUP
                + APPLICATIONS_PER_GROUP * PER_APPLICATION) + 6), APPLICATIONS_PER_GROUP);
    }

    @Test
    public void anApplicationChangeReachesOnlyThatApplication() {
        long id = GLOBAL + PER_GROUP + 1;
        assertChange(row(ConfigLevel.APPLICATION, "group0", "app0", "app.0", "2", id), 1);
    }

    private void assertChange(ConfigRow changed, int expectedPushes) {
        pushes.set(0);
        replicaRows.set(0);
        long started = System.nanoTime();
        service.apply(List.of(changed), changed.revision());
        long millis = millisSince(started);
        System.out.printf("A %s change: %d pushes in %d ms%n", changed.entry().level(), pushes.get(), millis);
        Assert.assertEquals(pushes.get(), expectedPushes);
        Assert.assertEquals(replicaRows.get(), 1);
        Assert.assertTrue(millis < LIMIT_MILLIS, "took " + millis + " ms");
    }

    private ConfigRow row(ConfigLevel level, String group, String application, String name, String value) {
        return row(level, group, application, name, value, nextId.getAndIncrement());
    }

    private ConfigRow row(ConfigLevel level, String group, String application, String name, String value, long id) {
        revision++;
        return new ConfigRow(new ConfigEntry(id, level, group, application, name, ConfigType.INTEGER, value, false,
                "", "2026-11-08T00:00:00Z", "test"), false, revision);
    }

    private static long millisSince(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
