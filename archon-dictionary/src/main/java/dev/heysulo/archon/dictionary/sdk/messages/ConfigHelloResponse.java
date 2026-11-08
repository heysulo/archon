package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * Whether archon-config accepted the caller. Accepted applications then receive {@link ConfigSnapshot} pieces, and
 * replicas {@link ConfigRows}.
 */
public class ConfigHelloResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final boolean accepted;
    private final String reason;

    private ConfigHelloResponse(boolean accepted, String reason) {
        this.accepted = accepted;
        this.reason = reason;
    }

    public static ConfigHelloResponse accepted() {
        return new ConfigHelloResponse(true, null);
    }

    public static ConfigHelloResponse refused(String reason) {
        return new ConfigHelloResponse(false, reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }
}
