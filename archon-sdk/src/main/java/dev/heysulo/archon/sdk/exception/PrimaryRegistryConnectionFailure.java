package dev.heysulo.archon.sdk.exception;

public class PrimaryRegistryConnectionFailure extends RuntimeException {
    public PrimaryRegistryConnectionFailure(Exception e) {
        super(e);
    }
}
