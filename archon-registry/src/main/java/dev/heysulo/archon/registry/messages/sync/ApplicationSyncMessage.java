package dev.heysulo.archon.registry.messages.sync;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.applications.Application;

public class ApplicationSyncMessage extends SyncMessage {
    String group;
    String name;
    int rank;
    RunLevel runLevel;
    String authenticationToken;

    public ApplicationSyncMessage(Application application) {
        this.group = application.getGroup();
        this.name = application.getName();
        this.rank = application.getRank();
        this.runLevel = application.getRunLevel();
        this.authenticationToken = application.getAuthenticationToken();
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public RunLevel getRunLevel() {
        return runLevel;
    }

    public void setRunLevel(RunLevel runLevel) {
        this.runLevel = runLevel;
    }

    public String getAuthenticationToken() {
        return authenticationToken;
    }

    public void setAuthenticationToken(String authenticationToken) {
        this.authenticationToken = authenticationToken;
    }
}
