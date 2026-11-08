package dev.heysulo.archon.sdk.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;

import java.util.List;

/** Where a {@link ConfigReplicaClient} keeps the rows it receives. */
public interface ConfigRowSink {
    /** The last revision held, sent on every reconnect so only what was missed comes back. 0 when empty. */
    long revision();

    /** Everything there is, replacing what was held. */
    void replace(List<ConfigRow> rows, long revision);

    /** Changes since the last batch, in revision order. Rows older than what is held are ignored. */
    void apply(List<ConfigRow> rows, long revision);
}
