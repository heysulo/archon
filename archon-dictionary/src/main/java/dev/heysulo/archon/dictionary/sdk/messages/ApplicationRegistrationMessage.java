package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.services.InstanceDetails;

public class ApplicationRegistrationMessage extends AuthenticatedMessage {
    private static final long serialVersionUID = 4082841948653972696L;

    String applicationName;
    String groupName;
    String ipAddress;
    int rank;
    // Absent from SDKs that predate it
    InstanceDetails details;

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

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    /** Where the instance runs, or null from an SDK that does not report it. */
    public InstanceDetails getDetails() {
        return details;
    }

    public void setDetails(InstanceDetails details) {
        this.details = details;
    }

    /**
     * On re-registration, whether the instance had acknowledged its rank, so the registry restores it as running
     * rather than starting. Null from older SDKs, which acknowledged every rank at once, and counts as ready.
     */
    private Boolean ready;

    public Boolean getReady() {
        return ready;
    }

    public void setReady(Boolean ready) {
        this.ready = ready;
    }
}
