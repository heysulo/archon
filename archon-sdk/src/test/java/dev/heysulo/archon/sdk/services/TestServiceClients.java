package dev.heysulo.archon.sdk.services;

import java.util.concurrent.Executors;

/** Service clients for tests in other packages. */
public final class TestServiceClients {
    private TestServiceClients() {
    }

    /** A client that never connects; closing it works. */
    public static ServiceClient unconnected(String serviceId, ServiceClientCallback callback) {
        return new ServiceClient(serviceId, id -> null, callback, null, Executors.newSingleThreadScheduledExecutor());
    }
}
