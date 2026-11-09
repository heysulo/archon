package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import dev.heysulo.databridge.core.common.Message;

import java.util.List;

/** From an application instance to the primary registry: log lines for whoever is watching. */
public class LogBatch implements Message {
    private static final long serialVersionUID = 1L;

    private final List<LogLine> lines;
    private final long dropped;
    private final boolean history;

    /**
     * @param dropped lines lost since the last batch because they came faster than they could be sent
     * @param history whether these are the lines kept from before streaming started
     */
    public LogBatch(List<LogLine> lines, long dropped, boolean history) {
        this.lines = List.copyOf(lines);
        this.dropped = dropped;
        this.history = history;
    }

    public List<LogLine> getLines() {
        return lines;
    }

    public long getDropped() {
        return dropped;
    }

    public boolean isHistory() {
        return history;
    }
}
