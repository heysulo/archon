package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * A round trip a service client sends before it reports a new connection as ready. The SDK on the service's side
 * answers it and never hands it to the application. Running the whole message path a few thousand times lets the
 * JVM compile it, so the application's first messages do not pay for interpreted code.
 */
public class ServiceWarmupMessage implements Message {
    private static final long serialVersionUID = 1L;

    private final long sequence;
    private final long sentAtNanos;
    private final boolean reply;

    public ServiceWarmupMessage(long sequence, long sentAtNanos, boolean reply) {
        this.sequence = sequence;
        this.sentAtNanos = sentAtNanos;
        this.reply = reply;
    }

    public ServiceWarmupMessage reply() {
        return new ServiceWarmupMessage(sequence, sentAtNanos, true);
    }

    public long getSequence() {
        return sequence;
    }

    /** The client's {@link System#nanoTime()} when it sent this round trip. Only meaningful to the client. */
    public long getSentAtNanos() {
        return sentAtNanos;
    }

    public boolean isReply() {
        return reply;
    }
}
