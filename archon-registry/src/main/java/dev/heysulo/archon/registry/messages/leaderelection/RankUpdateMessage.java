package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

public class RankUpdateMessage implements Message {
    int rank;

    public RankUpdateMessage(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }
}
