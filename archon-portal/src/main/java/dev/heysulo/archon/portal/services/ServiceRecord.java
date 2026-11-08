package dev.heysulo.archon.portal.services;

/**
 * A service definition as the portal shows it.
 *
 * @param portOffset      added to an instance's {@code ARCHON_BASE_PORT} to give the port it serves on
 * @param managedByArchon owned by Archon itself, such as {@code archon-config}; shown but not changeable here
 * @param createdAt       ISO timestamps, or null for one not yet stored
 */
public record ServiceRecord(String id, String name, int portOffset, String group, String application, String mode,
                            boolean managedByArchon, String createdAt, String updatedAt) {
}
