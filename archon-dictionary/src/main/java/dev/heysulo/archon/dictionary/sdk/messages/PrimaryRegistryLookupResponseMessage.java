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

    // Absent from registries that predate metrics, which read as false
    private boolean metricsAccepted;

    /**
     * Whether the sender understands {@link InstanceMetricsReport} and process lists carrying metrics. Senders from
     * before metrics leave it false, so a mixed cluster never sends one a class it cannot read, which would make it
     * close the connection.
     */
    public boolean isMetricsAccepted() {
        return metricsAccepted;
    }

    public void setMetricsAccepted(boolean metricsAccepted) {
        this.metricsAccepted = metricsAccepted;
    }

    /** The leadership term of the registry that answered. Registries that predate terms send 0. */
    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }
}
