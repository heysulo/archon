package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;

import java.util.List;

/**
 * Where a {@link ConfigReplicaClient} keeps the rows it receives: every stored configuration, deleted ones included.
 * Called on the replication's network thread, one call at a time. {@link ConfigReplica} is the ready-made one.
 */
public interface ConfigRowSink {
    /**
     * The last revision held, sent on every reconnect so only what was missed comes back.
     *
     * @return the revision, or 0 when empty, which asks for everything
     */
    long revision();

    /**
     * Everything there is, replacing what was held. Sent the first time, or when the server's history does not
     * cover the revision held.
     *
     * @param rows     every stored configuration that is not deleted
     * @param revision the server's revision once these are applied
     */
    void replace(List<ConfigRow> rows, long revision);

    /**
     * Changes since the last batch, in revision order. A row older than the one held for the same configuration must
     * be ignored.
     *
     * @param rows     the changed rows; a {@linkplain ConfigRow#deleted() deleted} one removes its configuration
     * @param revision the server's revision once these are applied
     */
    void apply(List<ConfigRow> rows, long revision);
}
