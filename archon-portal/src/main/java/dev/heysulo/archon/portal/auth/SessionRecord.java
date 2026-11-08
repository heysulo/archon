package dev.heysulo.archon.portal.auth;

import java.time.Instant;

/** A stored session, found by the hash of its token. */
public record SessionRecord(String tokenHash, long userId, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
}
