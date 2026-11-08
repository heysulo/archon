package dev.heysulo.archon.portal.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * Everything a portal user can be allowed to do. Roles grant permissions by key; {@link #ALL} grants every one,
 * including permissions added later. Every API endpoint and page checks one of these.
 */
public enum Permission {
    PROCESSES_VIEW("processes:view", "See running processes"),
    USERS_MANAGE("users:manage", "Create, change and delete users and assign roles"),
    CONFIGS_VIEW("configs:view", "See configurations"),
    CONFIGS_MANAGE("configs:manage", "Create, change and delete configurations");

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

    public static Optional<Permission> byKey(String key) {
        return Arrays.stream(values()).filter(permission -> permission.key.equals(key)).findFirst();
    }
}
