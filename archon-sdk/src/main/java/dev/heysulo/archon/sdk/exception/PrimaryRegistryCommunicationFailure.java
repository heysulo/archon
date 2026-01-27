package dev.heysulo.archon.sdk.exception;

public class PrimaryRegistryCommunicationFailure extends RuntimeException {
    public PrimaryRegistryCommunicationFailure(String stage, Exception e) {
        super(String.format("Primary Registry Communication Failure at stage %s", stage), e);
    }
}
