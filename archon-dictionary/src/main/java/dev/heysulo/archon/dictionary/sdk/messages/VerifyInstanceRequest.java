package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * Asks the primary registry whether an application instance is who it claims: running, with this group, name and
 * rank, and holding this registration token. Lets other Archon components trust an identity a caller presents.
 */
public class VerifyInstanceRequest implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String group;
    private final String name;
    private final int rank;
    private final String authenticationToken;

    public VerifyInstanceRequest(long requestId, String group, String name, int rank, String authenticationToken) {
        this.requestId = requestId;
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.authenticationToken = authenticationToken;
    }

    public long getRequestId() {
        return requestId;
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public int getRank() {
        return rank;
    }

    public String getAuthenticationToken() {
        return authenticationToken;
    }
}
