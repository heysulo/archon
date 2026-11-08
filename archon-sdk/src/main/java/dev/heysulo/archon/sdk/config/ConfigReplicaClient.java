package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHello;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRows;
import dev.heysulo.databridge.core.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Holds every stored configuration, for archon-config's mirrors and the portal. On each connect it says which
 * revision it has, receives only what it missed (or everything, the first time), then every change as the primary
 * makes it. Batches are applied whole, once their last piece arrives. It also manages configurations, for the
 * portal.
 */
public final class ConfigReplicaClient extends ConfigConnection implements ConfigAdmin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigReplicaClient.class);

    private final ConfigRowSink sink;
    private final CountDownLatch firstSync = new CountDownLatch(1);
    private volatile boolean synced;
    // The pieces of a batch so far. Touched only by the connection's thread.
    private List<ConfigRow> incoming;
    private boolean incomingReplaces;

    public ConfigReplicaClient(String group, String name, IntSupplier rank, Supplier<String> token,
            Connector connector, ConfigRowSink sink, ScheduledExecutorService scheduler) {
        super(group, name, rank, token, connector, scheduler);
        this.sink = sink;
    }

    /** Whether everything has arrived at least once. Later it may lag a moment behind while reconnecting. */
    public boolean isSynced() {
        return synced;
    }

    public boolean awaitSynced(long timeoutMillis) {
        try {
            return firstSync.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public ConfigEntry create(ConfigEntry entry, String actor) {
        return call(ConfigRequest.Operation.CREATE, entry, actor).get(0);
    }

    @Override
    public ConfigEntry update(ConfigEntry entry, String actor) {
        return call(ConfigRequest.Operation.UPDATE, entry, actor).get(0);
    }

    @Override
    public void delete(long id, String actor) {
        ConfigEntry target = new ConfigEntry(id, null, null, null, null, null, null, false, null, null, null);
        call(ConfigRequest.Operation.DELETE, target, actor);
    }

    @Override
    protected ConfigHello hello(int currentRank, String currentToken) {
        return new ConfigHello(group, name, currentRank, currentToken, true, sink.revision());
    }

    @Override
    protected void starting() {
        incoming = null;
    }

    @Override
    protected void received(Message message) {
        if (!(message instanceof ConfigRows batch)) {
            return;
        }
        if (batch.isReset() || incoming == null) {
            incoming = new ArrayList<>();
            incomingReplaces = batch.isReset();
        }
        incoming.addAll(batch.getRows());
        if (!batch.isLast()) {
            return;
        }
        List<ConfigRow> rows = incoming;
        incoming = null;
        if (incomingReplaces) {
            sink.replace(rows, batch.getRevision());
            LOGGER.info("Received all {} configurations from {} at revision {}", rows.size(), SERVICE,
                    batch.getRevision());
        } else {
            sink.apply(rows, batch.getRevision());
        }
        if (!synced) {
            synced = true;
            firstSync.countDown();
        }
    }
}
