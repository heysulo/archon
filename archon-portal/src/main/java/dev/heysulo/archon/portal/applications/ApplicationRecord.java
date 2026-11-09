package dev.heysulo.archon.portal.applications;

/**
 * An application definition as the portal shows it: the group and name its instances register with.
 *
 * @param builtIn   one of Archon's own components, always defined and not stored
 * @param createdAt ISO timestamps, or null for a built-in one
 */
public record ApplicationRecord(String group, String name, String description, boolean builtIn, String createdAt,
                                String updatedAt) {
}
