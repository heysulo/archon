package dev.heysulo.archon.portal.tls;

/**
 * A join token as the portal shows it. The token itself is shown once, when created, and never stored.
 *
 * @param reusable  whether any number of instances may join with it, as for instances that scale out
 * @param expiresAt when it stops working, or null for never
 * @param usedAt    when an instance last joined with it, or null if none has
 */
public record JoinTokenRecord(long id, String group, String name, String description, boolean reusable,
                              String expiresAt, String usedAt, int useCount, String createdBy, String createdAt) {
}
