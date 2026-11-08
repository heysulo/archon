package dev.heysulo.archon.sdk.exception;

/** Connecting to the primary registry failed or timed out. The SDK retries in the background after a drop. */
public class PrimaryRegistryConnectionFailure extends RuntimeException {
    /** @param e why the connection failed */
    public PrimaryRegistryConnectionFailure(Exception e) {
        super(e);
    }
}
