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

    /** Whether the user holds the permission, directly or through the one including it, or holds every one. */
    public boolean can(Permission permission) {
        return permissions.contains(Permission.ALL) || permissions.contains(permission.key())
                || permission.includedIn().map(including -> permissions.contains(including.key())).orElse(false);
    }
}
