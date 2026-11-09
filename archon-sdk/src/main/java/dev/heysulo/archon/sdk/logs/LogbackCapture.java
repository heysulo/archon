package dev.heysulo.archon.sdk.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import org.slf4j.LoggerFactory;

/** An appender on Logback's root logger. Loaded only when the application logs through Logback. */
final class LogbackCapture extends AppenderBase<ILoggingEvent> {
    private final LogBuffer buffer;

    private LogbackCapture(LogBuffer buffer) {
        this.buffer = buffer;
    }

    static void install(LogBuffer buffer) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        LogbackCapture appender = new LogbackCapture(buffer);
        appender.setContext(context);
        appender.setName("archon-live-logs");
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            message = message + "\n" + ThrowableProxyUtil.asString(thrown);
        }
        buffer.add(new LogLine(event.getTimeStamp(), event.getLevel().toString(), event.getLoggerName(),
                event.getThreadName(), message));
    }
}
