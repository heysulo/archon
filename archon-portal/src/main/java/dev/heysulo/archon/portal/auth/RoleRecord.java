package dev.heysulo.archon.portal.auth;

import java.util.Set;

/** A stored role and the permission keys it grants. */
public record RoleRecord(String id, String description, Set<String> permissions) {

    public RoleRecord {
        permissions = Set.copyOf(permissions);
    }
}
