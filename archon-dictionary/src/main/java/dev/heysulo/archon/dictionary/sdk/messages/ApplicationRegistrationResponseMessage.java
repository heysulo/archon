package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

public class ApplicationRegistrationResponseMessage extends AuthenticatedMessage {
    private static final long serialVersionUID = -8156020031660824629L;

    String applicationName;
    String applicationGroup;
    int rank;
    long term;

    public ApplicationRegistrationResponseMessage(String group, String name, int rank) {
        this.applicationName = name;
        this.applicationGroup = group;
        this.rank = rank;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getApplicationGroup() {
        return applicationGroup;
    }

    public void setApplicationGroup(String applicationGroup) {
        this.applicationGroup = applicationGroup;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    /** The leadership term of the registry that answered. Registries that predate terms send 0. */
    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }
}
