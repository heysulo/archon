package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.registry.client.LeaderElectorClient;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.databridge.core.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegistryApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(RegistryApplication.class);

    public RegistryApplication(String group, String name, int rank) {
        super(group, name, rank);
    }

    @Override
    public void OnDisconnect(Client client) {
        if (getRank() != Constants.RANK_PRIMARY) {
            logger.warn("{} disconnected", getDisplayName());
            return;
        }

        logger.error("Disconnected from the primary registry");
        RegistryServer registryServer = RegistryServer.getInstance();
        RegistryServer.setRank(Constants.RANK_FORECASTED_PRIMARY);
        try {
            LeaderElectorClient.getInstance().start();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        registryServer.start();
    }
}
