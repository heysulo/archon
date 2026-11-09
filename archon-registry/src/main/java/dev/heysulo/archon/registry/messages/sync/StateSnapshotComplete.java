package dev.heysulo.archon.registry.messages.sync;

/**
 * Sent by the primary after the last {@link ApplicationSyncMessage} of a state snapshot: the mirror now knows every
 * application the primary knows, so it could take over without refusing anyone's rank.
 */
public class StateSnapshotComplete extends SyncMessage {
    private static final long serialVersionUID = 1L;
}
