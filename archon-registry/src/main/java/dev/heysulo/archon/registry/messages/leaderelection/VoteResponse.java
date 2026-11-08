package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.databridge.core.common.Message;

public class VoteResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final long term;
    private final long knownTerm;
    private final boolean granted;

    /**
     * @param term      the term voted on
     * @param knownTerm the highest term the voter knows of, so a refused candidate can catch up
     */
    public VoteResponse(long term, long knownTerm, boolean granted) {
        this.term = term;
        this.knownTerm = knownTerm;
        this.granted = granted;
    }

    public long getTerm() {
        return term;
    }

    public long getKnownTerm() {
        return knownTerm;
    }

    public boolean isGranted() {
        return granted;
    }
}
