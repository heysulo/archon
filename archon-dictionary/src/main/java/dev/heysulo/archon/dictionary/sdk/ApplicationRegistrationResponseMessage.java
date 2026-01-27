package dev.heysulo.archon.dictionary.sdk;

import dev.heysulo.databridge.core.common.Message;

public class ApplicationRegistrationResponseMessage implements Message {
    String applicationName;
    String applicationGroup;
    int rank;

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
}
