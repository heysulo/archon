package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** A handler on java.util.logging's root logger, for applications logging through it. */
final class JulCapture extends Handler {
    private final LogBuffer buffer;
    private final SimpleFormatter formatter = new SimpleFormatter();

    private JulCapture(LogBuffer buffer) {
        this.buffer = buffer;
    }

    static void install(LogBuffer buffer) {
        Logger.getLogger("").addHandler(new JulCapture(buffer));
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        String message = formatter.formatMessage(record);
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            message = message + "\n" + trace.toString().stripTrailing();
        }
        buffer.add(new LogLine(record.getMillis(), level(record.getLevel()), record.getLoggerName(),
                Thread.currentThread().getName(), message));
    }

    // In the names the other libraries use, so the portal can filter one way
    static String level(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= Level.INFO.intValue()) {
            return "INFO";
        }
        return value >= Level.FINE.intValue() ? "DEBUG" : "TRACE";
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
