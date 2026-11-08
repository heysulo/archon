package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

public class ApplicationTerminationMessage implements Message {
    private static final long serialVersionUID = -6443257851982980085L;

    String reason;

    public ApplicationTerminationMessage(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
