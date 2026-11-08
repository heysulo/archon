package dev.heysulo.archon.dictionary.sdk.config;

import java.io.Serial;
import java.io.Serializable;

/**
 * A configuration as stored, for the replicas that hold every one: archon-config's mirrors and the portal.
 *
 * @param deleted  whether it was deleted; deleted rows are kept so a replica catching up hears about them
 * @param revision the revision of its last change. Every change gets the next revision, so a replica that has seen
 *                 up to revision N needs only the rows after N.
 */
public record ConfigRow(ConfigEntry entry, boolean deleted, long revision) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
