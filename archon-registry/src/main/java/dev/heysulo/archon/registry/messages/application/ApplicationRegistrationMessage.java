package dev.heysulo.archon.registry.messages.application;

import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.databridge.core.common.Message;

public class ApplicationRegistrationMessage implements Message {
    String applicationName;
    String applicationGroup;
    String displayName;
    int rank;

    public ApplicationRegistrationMessage(Application application) {
        this.applicationName = application.getName();
        this.applicationGroup = application.getGroup();
        this.displayName = application.getDisplayName();
        this.rank = application.getRank();
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }
}
