package dev.heysulo.archon.dictionary.sdk.enums;

public enum RunLevel {
    UNKNOWN(ProcessState.UNKNOWN),
    NOT_RUNNING(ProcessState.NOT_RUNNING),
    STARTING(ProcessState.RUNNING),
    RUNNING(ProcessState.RUNNING),
    CHANGING(ProcessState.RUNNING),
    SHUTTING_DOWN(ProcessState.RUNNING),
    FAILED(ProcessState.NOT_RUNNING),
    KILLED(ProcessState.NOT_RUNNING),
    TERMINATED(ProcessState.NOT_RUNNING);

    private final ProcessState processState;

    RunLevel(ProcessState processState) {
        this.processState = processState;
    }

    public ProcessState getProcessState() {
        return processState;
    }
}
