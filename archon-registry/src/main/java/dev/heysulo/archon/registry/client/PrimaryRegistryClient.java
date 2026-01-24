package dev.heysulo.archon.registry.client;

import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrimaryRegistryClient implements ClientCallback {
    private static final Logger logger = LoggerFactory.getLogger(PrimaryRegistryClient.class);

    @Override
    public void OnConnect(Client client) {

    }

    @Override
    public void OnDisconnect(Client client) {
        logger.error("Disconnected from the primary registry");
        RegistryServer registryServer = RegistryServer.getInstance();
        RegistryServer.setRank(Constants.RANK_FORECASTED_PRIMARY);
        LeaderElectorClient leaderElectorClient = new LeaderElectorClient(new NioEventLoopGroup());
        try {
            leaderElectorClient.start();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        registryServer.start();
    }

    @Override
    public void OnMessage(Client client, Message message) {

    }

    @Override
    public void OnError(Client client, Throwable throwable) {

    }
}
