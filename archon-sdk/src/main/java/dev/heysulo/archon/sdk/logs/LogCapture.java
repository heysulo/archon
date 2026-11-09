package dev.heysulo.archon.sdk.logs;

import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Captures this process's log lines so the portal can stream them live. Hooks into the logging library behind slf4j
 * where it has appenders (Logback, Log4j 2, java.util.logging), and otherwise captures the console, as for
 * slf4j-simple. The application's own logging is unchanged: lines still go wherever they went.
 *
 * <p>Set {@code ARCHON_LOG_CAPTURE=off} to capture nothing.
 */
public final class LogCapture {
    public static final String ENV = "ARCHON_LOG_CAPTURE";
    private static String installed;

    private LogCapture() {
    }

    /** What was hooked into, such as {@code logback} or {@code console}, or null if nothing yet. */
    public static synchronized String installed() {
        return installed;
    }

    /**
     * Starts capturing, once per process.
     *
     * @return what was hooked into, or {@code off}
     */
    public static synchronized String install() {
        if (installed != null) {
            return installed;
        }
        String setting = System.getenv(ENV);
        if (setting != null && setting.trim().equalsIgnoreCase("off")) {
            installed = "off";
            return installed;
        }
        LogBuffer buffer = LogBuffer.getInstance();
        String backend = backend(LoggerFactory.getILoggerFactory().getClass().getName());
        try {
            switch (backend) {
                case "logback" -> LogbackCapture.install(buffer);
                case "log4j" -> Log4jCapture.install(buffer);
                case "jul" -> JulCapture.install(buffer);
                default -> ConsoleCapture.install(buffer);
            }
        } catch (LinkageError | RuntimeException e) {
            // An unexpected version of the library: the console still carries the lines
            backend = fallBackToConsole(buffer);
        }
        installed = backend;
        return installed;
    }

    // Live logs are a convenience: failing to capture them must never stop the application starting
    private static String fallBackToConsole(LogBuffer buffer) {
        try {
            ConsoleCapture.install(buffer);
            return "console";
        } catch (LinkageError | RuntimeException e) {
            LoggerFactory.getLogger(LogCapture.class).warn("Cannot capture logs for the portal: {}", e.toString());
            return "off";
        }
    }

    /** Which library slf4j logs through, from its logger factory's class. */
    static String backend(String factory) {
        String name = factory.toLowerCase(Locale.ROOT);
        if (name.startsWith("ch.qos.logback")) {
            return "logback";
        }
        if (name.startsWith("org.apache.logging")) {
            return "log4j";
        }
        if (name.contains("jdk14") || name.contains(".jul.")) {
            return "jul";
        }
        return "console";
    }
}
