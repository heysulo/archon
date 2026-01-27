package dev.heysulo.archon.dictionary.sdk;

import dev.heysulo.databridge.core.common.Message;

public class ApplicationRankUpdate implements Message {
    int rank;

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }
}
