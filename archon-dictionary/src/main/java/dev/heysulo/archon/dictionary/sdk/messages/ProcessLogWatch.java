package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * To the primary registry, from the portal: start or stop relaying an application instance's log lines on this
 * connection. Disconnecting stops every watch the connection held.
 */
public class ProcessLogWatch implements Message {
    private static final long serialVersionUID = 1L;

    private final String group;
    private final String name;
    private final int rank;
    private final boolean watch;

    public ProcessLogWatch(String group, String name, int rank, boolean watch) {
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.watch = watch;
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

    public boolean isWatch() {
        return watch;
    }
}
