package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures what the process writes to standard output and error, still writing it there. Used when the logging
 * library has no appenders to hook into, as with slf4j-simple, whose lines it reads the level, thread and logger
 * from. Other lines keep the level of the line they continue, as a stack trace does, or else take the stream's name.
 */
final class ConsoleCapture {
    // [thread] LEVEL logger - message, as slf4j-simple writes it
    static final Pattern SIMPLE = Pattern.compile("^\\[(.+?)] (TRACE|DEBUG|INFO|WARN|ERROR) (\\S+) - (.*)$",
            Pattern.DOTALL);
    private static final int MAX_LINE_BYTES = LogLine.MAX_MESSAGE * 4;

    private ConsoleCapture() {
    }

    static void install(LogBuffer buffer) {
        System.setOut(tee(System.out, "STDOUT", buffer));
        System.setErr(tee(System.err, "STDERR", buffer));
    }

    static PrintStream tee(PrintStream original, String stream, LogBuffer buffer) {
        // PrintStream.charset() is newer than Java 17; the console's own encoding is the default one there
        Charset charset = Charset.defaultCharset();
        return new PrintStream(new LineSplitter(original, stream, buffer, charset), true, charset);
    }

    /** Reads a line as slf4j-simple writes it, or as plain output continuing {@code previous}. */
    static LogLine parse(String text, String stream, String previousLevel, long time) {
        Matcher matcher = SIMPLE.matcher(text);
        if (matcher.matches()) {
            return new LogLine(time, matcher.group(2), matcher.group(3), matcher.group(1), matcher.group(4));
        }
        boolean continuation = previousLevel != null && (text.startsWith("\t") || text.startsWith(" ")
                || text.startsWith("Caused by:") || text.startsWith("Suppressed:"));
        return new LogLine(time, continuation ? previousLevel : stream, "", "", text);
    }

    /** Passes every byte on, and hands each complete line to the buffer. */
    private static final class LineSplitter extends OutputStream {
        private final PrintStream original;
        private final String stream;
        private final LogBuffer buffer;
        private final Charset charset;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private String previousLevel;

        LineSplitter(PrintStream original, String stream, LogBuffer buffer, Charset charset) {
            this.original = original;
            this.stream = stream;
            this.buffer = buffer;
            this.charset = charset;
        }

        @Override
        public synchronized void write(int b) {
            original.write(b);
            take(b);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            original.write(bytes, offset, length);
            for (int i = offset; i < offset + length; i++) {
                take(bytes[i]);
            }
        }

        @Override
        public synchronized void flush() {
            original.flush();
        }

        private void take(int b) {
            if (b == '\n') {
                emit();
            } else if (b != '\r' && line.size() < MAX_LINE_BYTES) {
                line.write(b);
            }
        }

        private void emit() {
            String text = line.toString(charset);
            line.reset();
            if (text.isEmpty()) {
                return;
            }
            try {
                LogLine parsed = parse(text, stream, previousLevel, System.currentTimeMillis());
                previousLevel = parsed.level();
                buffer.add(parsed);
            } catch (RuntimeException ignored) {
                // Capturing must never break the application's own output
            }
        }
    }
}
