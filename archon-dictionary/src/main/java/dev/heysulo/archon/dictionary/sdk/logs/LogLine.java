package dev.heysulo.archon.dictionary.sdk.logs;

import java.io.Serial;
import java.io.Serializable;

/**
 * One line an application instance logged.
 *
 * @param time    when, in milliseconds since the epoch
 * @param level   TRACE, DEBUG, INFO, WARN or ERROR when the logging library says; STDOUT or STDERR for console output
 *                it cannot read a level from
 * @param logger  the logger's name, or empty if unknown
 * @param thread  the thread that logged it, or empty if unknown
 * @param message the message, with any stack trace on the following lines
 */
public record LogLine(long time, String level, String logger, String thread, String message) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    /** Longer messages are cut, so one line cannot fill a frame. */
    public static final int MAX_MESSAGE = 16 * 1024;

    public LogLine {
        level = level == null ? "" : level;
        logger = logger == null ? "" : logger;
        thread = thread == null ? "" : thread;
        message = message == null ? "" : message.length() > MAX_MESSAGE
                ? message.substring(0, MAX_MESSAGE) + " … (cut)" : message;
    }
}
