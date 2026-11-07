package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.databridge.core.common.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegistryApplication extends Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryApplication.class);

    public RegistryApplication(String group, String name, int rank) {
        super(group, name, rank);
    }

    @Override
    public void onDisconnect(Connection connection) {
        if (getRank() != Constants.RANK_PRIMARY) {
            LOGGER.warn("{} disconnected", getDisplayName());
            setRunLevel(RunLevel.KILLED);
            return;
        }

        LOGGER.error("Disconnected from the primary registry");
        RegistryServer.getInstance().handlePrimaryLost();
    }
}
