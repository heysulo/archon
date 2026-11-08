package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.config.ConfigRow;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Stored configurations for a replica, in revision order: first what it missed, possibly in several pieces, then
 * every change as the primary makes it.
 */
public class ConfigRows implements Message {
    private static final long serialVersionUID = 1L;

    private final ArrayList<ConfigRow> rows;
    private final long revision;
    private final boolean reset;
    private final boolean last;

    /**
     * @param revision the primary's revision once these rows are applied
     * @param reset    on the first piece only: forget everything held, these rows and the pieces after them are all
     *                 there is
     * @param last     whether this is the last piece of the batch; the replica applies a batch whole
     */
    public ConfigRows(List<ConfigRow> rows, long revision, boolean reset, boolean last) {
        this.rows = new ArrayList<>(rows);
        this.revision = revision;
        this.reset = reset;
        this.last = last;
    }

    public List<ConfigRow> getRows() {
        return List.copyOf(rows);
    }

    public long getRevision() {
        return revision;
    }

    public boolean isReset() {
        return reset;
    }

    public boolean isLast() {
        return last;
    }
}
