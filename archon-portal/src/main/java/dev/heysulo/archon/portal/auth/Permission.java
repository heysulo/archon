package dev.heysulo.archon.portal.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * Everything a portal user can be allowed to do. Roles grant permissions by key; {@link #ALL} grants every one,
 * including permissions added later. Every API endpoint and page checks one of these.
 */
public enum Permission {
    PROCESSES_VIEW("processes:view", "See running processes"),
    LOGS_VIEW("logs:view", "Watch processes' logs live. Logs can hold secrets, so grant it with care"),
    APPLICATIONS_VIEW("applications:view", "See which applications are defined"),
    APPLICATIONS_MANAGE("applications:manage", "Define applications, so their instances may register, and remove "
            + "definitions"),
    USERS_VIEW("users:view", "See users and the roles they hold"),
    USERS_MANAGE("users:manage", "Create, change and delete users and assign them roles"),
    ROLES_VIEW("roles:view", "See roles and their permissions"),
    ROLES_MANAGE("roles:manage", "Create, change and delete roles"),
    CONFIGS_VIEW("configs:view", "See configurations"),
    CONFIGS_MANAGE("configs:manage", "Create, change and delete configurations"),
    SERVICES_VIEW("services:view", "See service definitions"),
    SERVICES_MANAGE("services:manage", "Create, change and delete service definitions");

    /** Grants every permission. */
    public static final String ALL = "*";

    private final String key;
    private final String description;

    Permission(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String description() {
        return description;
    }

    /**
     * The permission that includes this one: modifying something includes seeing it, so {@code configs:manage}
     * includes {@code configs:view}.
     *
     * @return the {@code manage} permission of the same feature, for a {@code view} permission that has one
     */
    public Optional<Permission> includedIn() {
        return key.endsWith(":view") ? byKey(key.substring(0, key.length() - ":view".length()) + ":manage")
                : Optional.empty();
    }

    public static Optional<Permission> byKey(String key) {
        return Arrays.stream(values()).filter(permission -> permission.key.equals(key)).findFirst();
    }
}
