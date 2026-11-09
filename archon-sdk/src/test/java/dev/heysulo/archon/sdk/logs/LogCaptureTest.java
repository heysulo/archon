package dev.heysulo.archon.sdk.logs;

import dev.heysulo.archon.dictionary.sdk.logs.LogLine;
import dev.heysulo.archon.dictionary.sdk.messages.LogBatch;
import dev.heysulo.databridge.core.common.Message;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class LogCaptureTest {

    private static LogLine line(String message) {
        return new LogLine(1, "INFO", "", "", message);
    }

    @Test
    public void readsSimpleLoggerLinesAndTheirContinuations() {
        LogLine line = ConsoleCapture.parse("[main] WARN dev.heysulo.Example - Slow start", "STDERR", null, 5);
        Assert.assertEquals(line, new LogLine(5, "WARN", "dev.heysulo.Example", "main", "Slow start"));
        LogLine trace = ConsoleCapture.parse("\tat dev.heysulo.Example.run(Example.java:9)", "STDERR", "ERROR", 6);
        Assert.assertEquals(trace.level(), "ERROR", "a stack trace keeps the level of its line");
        Assert.assertEquals(ConsoleCapture.parse("java.net.SocketException: Connection reset", "STDERR", "WARN", 6)
                .level(), "WARN", "a stack trace's first line belongs to the line that logged it");
        Assert.assertEquals(ConsoleCapture.parse("io.netty.handler.codec.DecoderException", "STDERR", "INFO", 6)
                .level(), "INFO");
        Assert.assertEquals(ConsoleCapture.parse("Exception in thread \"main\" java.lang.Error", "STDERR", "INFO", 6)
                .level(), "STDERR", "an uncaught exception stands alone");
        Assert.assertEquals(ConsoleCapture.parse("Hello", "STDOUT", "ERROR", 7).level(), "STDOUT");
        Assert.assertEquals(ConsoleCapture.parse("Hello", "STDOUT", null, 7).level(), "STDOUT");
    }

    @Test
    public void capturesTheConsoleAndStillWritesIt() {
        LogBuffer buffer = LogBuffer.create(10, 10);
        // These lines are INFO, below the WARN kept by default
        buffer.setMinimumLevel("INFO");
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        PrintStream tee = ConsoleCapture.tee(new PrintStream(original, true, StandardCharsets.UTF_8), "STDOUT",
                buffer);
        tee.println("[worker-1] INFO com.example.Main - Ready");
        tee.print("partial");
        tee.println(" line");

        Assert.assertEquals(original.toString(StandardCharsets.UTF_8),
                "[worker-1] INFO com.example.Main - Ready" + System.lineSeparator() + "partial line"
                        + System.lineSeparator());
        List<LogLine> lines = buffer.startStreaming();
        Assert.assertEquals(lines.stream().map(LogLine::message).toList(), List.of("Ready", "partial line"));
        Assert.assertEquals(lines.get(0).thread(), "worker-1");
    }

    @Test
    public void keepsRecentLinesAndQueuesOnlyWhileStreaming() {
        LogBuffer buffer = LogBuffer.create(3, 2);
        // These lines are INFO, below the WARN kept by default
        buffer.setMinimumLevel("INFO");
        for (int i = 0; i < 5; i++) {
            buffer.add(line("before " + i));
        }
        Assert.assertEquals(buffer.startStreaming().stream().map(LogLine::message).toList(),
                List.of("before 2", "before 3", "before 4"), "the latest are kept");
        buffer.add(line("a"));
        buffer.add(line("b"));
        buffer.add(line("c"));
        LogBuffer.Drained drained = buffer.drain(10);
        Assert.assertEquals(drained.lines().stream().map(LogLine::message).toList(), List.of("a", "b"));
        Assert.assertEquals(drained.dropped(), 1, "the queue is bounded");
        buffer.stopStreaming();
        buffer.add(line("d"));
        Assert.assertTrue(buffer.drain(10).lines().isEmpty(), "nothing queues once nobody watches");
    }

    @Test
    public void sendsHistoryFirstThenBatches() throws Exception {
        LogBuffer buffer = LogBuffer.create(10, 10);
        // These lines are INFO, below the WARN kept by default
        buffer.setMinimumLevel("INFO");
        buffer.add(line("old"));
        List<Message> sent = new CopyOnWriteArrayList<>();
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
        LogShipper shipper = new LogShipper(buffer, sent::add, thread);
        try {
            shipper.start();
            buffer.add(line("new"));
            long deadline = System.currentTimeMillis() + 3_000;
            while (sent.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            LogBatch history = (LogBatch) sent.get(0);
            Assert.assertTrue(history.isHistory());
            Assert.assertEquals(history.getLines().get(0).message(), "old");
            LogBatch live = (LogBatch) sent.get(1);
            Assert.assertFalse(live.isHistory());
            Assert.assertEquals(live.getLines().stream().map(LogLine::message).collect(Collectors.toList()),
                    List.of("new"));

            shipper.stop();
            Assert.assertFalse(shipper.isRunning());
            Assert.assertTrue(buffer.drain(10).lines().isEmpty());
        } finally {
            thread.shutdownNow();
        }
    }

    @Test
    public void tellsLoggingLibrariesApart() {
        Assert.assertEquals(LogCapture.backend("ch.qos.logback.classic.LoggerContext"), "logback");
        Assert.assertEquals(LogCapture.backend("org.apache.logging.slf4j.Log4jLoggerFactory"), "log4j");
        Assert.assertEquals(LogCapture.backend("org.slf4j.impl.JDK14LoggerFactory"), "jul");
        Assert.assertEquals(LogCapture.backend("org.slf4j.impl.SimpleLoggerFactory"), "console");
        Assert.assertEquals(JulCapture.level(Level.SEVERE), "ERROR");
        Assert.assertEquals(JulCapture.level(Level.WARNING), "WARN");
        Assert.assertEquals(JulCapture.level(Level.FINEST), "TRACE");
    }
}
