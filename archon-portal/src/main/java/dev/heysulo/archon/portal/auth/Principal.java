package dev.heysulo.archon.portal.auth;

import java.util.Set;

/**
 * A signed-in user and what they may do.
 *
 * @param permissions permission keys from all of the user's roles, possibly {@link Permission#ALL}
 */
public record Principal(long userId, String username, String displayName, Set<String> roles,
                        Set<String> permissions) {

    public Principal {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    public boolean can(Permission permission) {
        return permissions.contains(Permission.ALL) || permissions.contains(permission.key());
    }
}
