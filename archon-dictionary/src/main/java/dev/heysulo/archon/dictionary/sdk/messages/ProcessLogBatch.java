package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import dev.heysulo.databridge.core.common.Message;

import java.util.List;

/**
 * From the primary registry to a watcher: an application instance's log lines, or news about the stream, such as
 * the instance disconnecting.
 */
public class ProcessLogBatch implements Message {
    private static final long serialVersionUID = 1L;

    private final String group;
    private final String name;
    private final int rank;
    private final List<LogLine> lines;
    private final long dropped;
    private final boolean history;
    private final String status;

    /**
     * @param status what happened to the stream, such as the instance disconnecting, or null
     */
    public ProcessLogBatch(String group, String name, int rank, List<LogLine> lines, long dropped, boolean history,
            String status) {
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.lines = List.copyOf(lines);
        this.dropped = dropped;
        this.history = history;
        this.status = status;
    }

    public static ProcessLogBatch status(String group, String name, int rank, String status) {
        return new ProcessLogBatch(group, name, rank, List.of(), 0, false, status);
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public int getRank() {
        return rank;
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

    public String getStatus() {
        return status;
    }
}
