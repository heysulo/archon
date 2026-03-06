package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

public class PrimaryRegistryLookupResponseMessage implements Message {
    int rank;

    public PrimaryRegistryLookupResponseMessage(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }
}
