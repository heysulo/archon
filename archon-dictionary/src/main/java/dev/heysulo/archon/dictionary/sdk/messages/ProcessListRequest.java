package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** Asks the primary registry for every application instance it knows. Needs no registration. */
public class ProcessListRequest implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;

    public ProcessListRequest(long requestId) {
        this.requestId = requestId;
    }

    public long getRequestId() {
        return requestId;
    }
}
