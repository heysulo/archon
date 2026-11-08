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
    private final boolean everything;
    private final long sinceRevision;

    /** An application: it receives what it sees, most specific per name, then every change to that. */
    public ConfigHello(String group, String name, int rank, String authenticationToken) {
        this(group, name, rank, authenticationToken, false, 0);
    }

    /**
     * @param everything    a replica, only archon-config's mirrors and the portal: it receives every stored row, then
     *                      every change
     * @param sinceRevision for a replica, the last revision it has, so it receives only what it missed; 0 for all
     */
    public ConfigHello(String group, String name, int rank, String authenticationToken, boolean everything,
            long sinceRevision) {
        this.group = group;
        this.name = name;
        this.rank = rank;
        this.authenticationToken = authenticationToken;
        this.everything = everything;
        this.sinceRevision = sinceRevision;
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

    public boolean isEverything() {
        return everything;
    }

    public long getSinceRevision() {
        return sinceRevision;
    }
}
