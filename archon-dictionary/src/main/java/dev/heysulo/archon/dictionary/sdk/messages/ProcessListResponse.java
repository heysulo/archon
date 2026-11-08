package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.archon.dictionary.sdk.services.ProcessInfo;
import dev.heysulo.databridge.core.common.Message;

import java.util.ArrayList;
import java.util.List;

/** Every application instance the primary registry knows, in any state. */
public class ProcessListResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final long term;
    private final ArrayList<ProcessInfo> processes;

    /**
     * @param term the answering primary's leadership term
     */
    public ProcessListResponse(long requestId, long term, List<ProcessInfo> processes) {
        this.requestId = requestId;
        this.term = term;
        this.processes = new ArrayList<>(processes);
    }

    public long getRequestId() {
        return requestId;
    }

    public long getTerm() {
        return term;
    }

    public List<ProcessInfo> getProcesses() {
        return List.copyOf(processes);
    }
}
