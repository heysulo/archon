package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * From the primary registry to an application instance: start sending its log lines, as someone started watching
 * them, or stop, as the last one stopped. On starting, the instance first sends the lines it kept from before.
 */
public class LogStreamControl implements Message {
    private static final long serialVersionUID = 1L;

    private final boolean streaming;

    public LogStreamControl(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean isStreaming() {
        return streaming;
    }
}
