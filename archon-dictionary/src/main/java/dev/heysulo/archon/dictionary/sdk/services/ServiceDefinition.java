package dev.heysulo.archon.dictionary.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * A service as the registry has it configured. Only instances of the owning application definition may start it,
 * whatever their rank.
 *
 * @param id         the identifier applications start and look up the service by
 * @param name       a human readable name
 * @param portOffset added to the starting instance's base port to give the service's port
 * @param group      the group of the application definition allowed to start it
 * @param application the name of the application definition allowed to start it
 * @param mode       how its running instances share the work
 */
public record ServiceDefinition(String id, String name, int portOffset, String group, String application,
                                ServiceMode mode) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public ServiceDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(mode, "mode");
        if (portOffset < 0) {
            throw new IllegalArgumentException("portOffset must not be negative: " + portOffset);
        }
    }

    /** Whether instances of this application definition may start the service. */
    public boolean isOwnedBy(String group, String application) {
        return this.group.equals(group) && this.application.equals(application);
    }
}
