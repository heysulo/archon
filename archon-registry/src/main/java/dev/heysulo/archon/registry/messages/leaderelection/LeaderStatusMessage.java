package dev.heysulo.archon.registry.messages.leaderelection;

import java.time.Instant;

import static dev.heysulo.archon.registry.constants.Constants.RANK_FORECASTED_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

public class LeaderStatusMessage extends LeaderElectionMessage {
    int rank;

    public LeaderStatusMessage(int rank, String address, Instant startTime) {
        super(address, startTime);
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public boolean isPrimary() {
        return rank == RANK_PRIMARY;
    }

    public boolean isForecastedPrimary() {
        return rank == RANK_FORECASTED_PRIMARY;
    }
}
