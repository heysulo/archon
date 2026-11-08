package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

public class ApplicationRankUpdate extends AuthenticatedMessage {
    private static final long serialVersionUID = -3362385395370685908L;

    int rank;

    public ApplicationRankUpdate(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }
}
