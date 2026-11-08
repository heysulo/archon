package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

public class PrimaryRegistryLookupResponseMessage implements Message {
    private static final long serialVersionUID = -8989198459934245626L;

    int rank;
    long term;

    public PrimaryRegistryLookupResponseMessage(int rank) {
        this.rank = rank;
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
