package dev.heysulo.archon.portal;

import dev.heysulo.archon.sdk.config.ConfigAdmin;
import dev.heysulo.archon.sdk.config.ConfigReplica;
import dev.heysulo.archon.sdk.config.ConfigReplicaClient;
import dev.heysulo.archon.sdk.config.ConfigurationException;

/**
 * Every configuration, held in this portal replica's memory and kept current by archon-config's primary, and the
 * way to change them. Lists are served from memory; changes go to the primary, which pushes them back to every
 * portal replica.
 */
final class PortalConfigs implements ConfigApi.Source {
    private final ConfigReplica store = new ConfigReplica();
    private volatile ConfigReplicaClient client;

    /** Starts replicating; called once the portal is registered, since archon-config checks who it is. */
    void start(ConfigReplicaClient replicating) {
        this.client = replicating;
    }

    ConfigReplica store() {
        return store;
    }

    @Override
    public ConfigReplica replica() {
        return store;
    }

    @Override
    public boolean synced() {
        ConfigReplicaClient current = client;
        return current != null && current.isSynced();
    }

    @Override
    public ConfigAdmin admin() {
        ConfigReplicaClient current = client;
        if (current == null) {
            throw ConfigurationException.unavailable("The portal is not registered yet", null);
        }
        return current;
    }
}
