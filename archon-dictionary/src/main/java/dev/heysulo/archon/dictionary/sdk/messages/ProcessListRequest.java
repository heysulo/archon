package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** Asks the primary registry for every application instance it knows. Needs no registration. */
public class ProcessListRequest implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;

    public ProcessListRequest(long requestId) {
        this.requestId = requestId;
    }

    // Absent from portals that predate metrics, which read as false
    private boolean metricsAccepted;

    public long getRequestId() {
        return requestId;
    }

    /** Whether the sender can read process lists carrying metrics; older ones are sent lists without. */
    public boolean isMetricsAccepted() {
        return metricsAccepted;
    }

    public ProcessListRequest acceptingMetrics() {
        this.metricsAccepted = true;
        return this;
    }
}
