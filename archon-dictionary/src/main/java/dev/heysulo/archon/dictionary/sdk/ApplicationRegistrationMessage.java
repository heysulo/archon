package dev.heysulo.archon.dictionary.sdk;

import dev.heysulo.databridge.core.common.Message;

public class ApplicationRegistrationMessage implements Message {
    String applicationName;
    String groupName;
    String ipAddress;

    public ApplicationRegistrationMessage(String applicationName, String groupName, String ipAddress) {
        this.applicationName = applicationName;
        this.groupName = groupName;
        this.ipAddress = ipAddress;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}
