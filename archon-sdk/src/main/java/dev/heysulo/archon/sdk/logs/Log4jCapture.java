package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * An appender on Log4j 2's root logger. Loaded only when the application logs through Log4j 2. A reconfiguration of
 * Log4j, as from a changed configuration file, removes it.
 */
final class Log4jCapture extends AbstractAppender {
    private final LogBuffer buffer;

    private Log4jCapture(LogBuffer buffer) {
        super("archon-live-logs", null, null, true, Property.EMPTY_ARRAY);
        this.buffer = buffer;
    }

    static void install(LogBuffer buffer) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        Log4jCapture appender = new Log4jCapture(buffer);
        appender.start();
        configuration.addAppender(appender);
        configuration.getRootLogger().addAppender(appender, null, null);
        context.updateLoggers();
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (event.getThrown() != null) {
            StringWriter trace = new StringWriter();
            event.getThrown().printStackTrace(new PrintWriter(trace));
            message = message + "\n" + trace.toString().stripTrailing();
        }
        buffer.add(new LogLine(event.getTimeMillis(), event.getLevel().name(), event.getLoggerName(),
                event.getThreadName(), message));
    }
}
