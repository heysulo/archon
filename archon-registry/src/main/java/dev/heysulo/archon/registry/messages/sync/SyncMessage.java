package dev.heysulo.archon.registry.messages.sync;

import dev.heysulo.databridge.core.common.Message;

import java.util.concurrent.atomic.AtomicLong;

public abstract class SyncMessage implements Message {
    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong(0);

    private final long sequenceId;

    public SyncMessage() {
        this.sequenceId = SEQUENCE_GENERATOR.incrementAndGet();
    }

    public long getSequenceId() {
        return sequenceId;
    }
}
