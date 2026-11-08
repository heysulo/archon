package dev.heysulo.archon.portal.auth;

import java.time.Instant;
import java.util.Set;

/** A stored user. The password hash never leaves the portal's process. */
public record UserRecord(long id, String username, String displayName, String passwordHash, boolean enabled,
                         Set<String> roles, Instant createdAt, Instant lastLoginAt) {

    public UserRecord {
        roles = Set.copyOf(roles);
    }
}
