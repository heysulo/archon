package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * The first message on a connection to archon-config: who the caller is, as the registry knows it. archon-config
 * asks the primary registry to confirm the token before it serves anything.
 */
public class ConfigHello implements Message {
    private static final long serialVersionUID = 1L;

    private final String group;
    private final String name;
    private final int rank;
    private final String authenticationToken;

    public ConfigHello(String group, String name, int rank, String authenticationToken) {
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.authenticationToken = authenticationToken;
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
