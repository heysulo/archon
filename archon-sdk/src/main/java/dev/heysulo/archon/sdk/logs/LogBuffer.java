package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Where captured log lines wait. The latest lines at or above a level are kept in memory, so a new watcher sees what
 * happened just before; while someone is watching, every line also queues to be sent, whatever its level. Both are
 * bounded: logging never waits for the network, and a line that does not fit in the queue is counted as dropped.
 * How many lines are kept, and from which level, follow {@link LogSettings}.
 */
public final class LogBuffer {
    static final int HISTORY = 1_000;
    static final int QUEUE = 10_000;
    private static final LogBuffer INSTANCE = new LogBuffer(HISTORY, QUEUE);

    private int historySize;
    private int minimumSeverity = severity(LogSettings.DEFAULT_LEVEL);
    private final int queueSize;
    private final Deque<LogLine> history = new ArrayDeque<>();
    private final Deque<LogLine> queue = new ArrayDeque<>();
    private boolean streaming;
    private long dropped;

    private LogBuffer(int historySize, int queueSize) {
        this.historySize = historySize;
        this.queueSize = queueSize;
    }

    /** A separate buffer, as tests need. */
    static LogBuffer create(int historySize, int queueSize) {
        return new LogBuffer(historySize, queueSize);
    }

    public static LogBuffer getInstance() {
        return INSTANCE;
    }

    /** Takes a line. Called on the logging thread, so it only stores it. */
    public synchronized void add(LogLine line) {
        if (historySize > 0 && severity(line.level()) >= minimumSeverity) {
            if (history.size() >= historySize) {
                history.removeFirst();
            }
            history.addLast(line);
        }
        if (!streaming) {
            return;
        }
        if (queue.size() == queueSize) {
            dropped++;
        } else {
            queue.addLast(line);
        }
    }

    /** How many lines to keep in memory; the oldest go at once if there are more. */
    synchronized void setHistorySize(int lines) {
        historySize = Math.max(0, lines);
        while (history.size() > historySize) {
            history.removeFirst();
        }
    }

    synchronized int historySize() {
        return historySize;
    }

    /** The lowest level kept in memory, such as WARN; lines below it are only streamed live. */
    synchronized void setMinimumLevel(String level) {
        minimumSeverity = severity(level);
        history.removeIf(line -> severity(line.level()) < minimumSeverity);
    }

    /**
     * How serious a level is. Console output without a level counts as INFO from standard output and ERROR from
     * standard error, where uncaught exceptions land.
     */
    static int severity(String level) {
        return switch (level == null ? "" : level) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "WARN" -> 3;
            case "ERROR", "STDERR" -> 4;
            default -> 2;
        };
    }

    /**
     * Starts queueing lines to send.
     *
     * @return the lines kept from before, oldest first, which the queue does not repeat
     */
    synchronized List<LogLine> startStreaming() {
        streaming = true;
        queue.clear();
        dropped = 0;
        return new ArrayList<>(history);
    }

    synchronized void stopStreaming() {
        streaming = false;
        queue.clear();
        dropped = 0;
    }

    synchronized boolean isStreaming() {
        return streaming;
    }

    /** What to send next: up to {@code max} queued lines, and how many were dropped since the last call. */
    synchronized Drained drain(int max) {
        List<LogLine> lines = new ArrayList<>(Math.min(max, queue.size()));
        while (lines.size() < max && !queue.isEmpty()) {
            lines.add(queue.removeFirst());
        }
        long lost = dropped;
        dropped = 0;
        return new Drained(lines, lost);
    }

    record Drained(List<LogLine> lines, long dropped) {
    }
}
