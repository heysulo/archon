package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** Whether the instance in a {@link VerifyInstanceRequest} is genuine. Says nothing more, either way. */
public class VerifyInstanceResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final boolean valid;

    public VerifyInstanceResponse(long requestId, boolean valid) {
        this.requestId = requestId;
        this.valid = valid;
    }

    public long getRequestId() {
        return requestId;
    }

    public boolean isValid() {
        return valid;
    }
}
