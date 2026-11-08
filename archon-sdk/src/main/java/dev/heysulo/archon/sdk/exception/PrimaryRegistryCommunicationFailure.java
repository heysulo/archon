package dev.heysulo.archon.sdk.exception;

/**
 * {@link dev.heysulo.archon.sdk.ArchonInterface#initialize} could not complete a step with the primary registry,
 * such as getting a rank in time. The instance is not registered; a process usually exits and is restarted.
 */
public class PrimaryRegistryCommunicationFailure extends RuntimeException {
    /**
     * @param stage the step that failed, such as {@code REGISTRATION_RANK_UPDATE}
     * @param e     why it failed
     */
    public PrimaryRegistryCommunicationFailure(String stage, Exception e) {
        super(String.format("Primary Registry Communication Failure at stage %s", stage), e);
    }
}
