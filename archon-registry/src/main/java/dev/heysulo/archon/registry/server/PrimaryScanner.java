package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupMessage;
import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.messages.leaderelection.PrimaryStanding;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

/**
 * Lets a primary find every other registry, including ones it lost to a partition. When another primary answers,
 * the two exchange standings to decide which one stays.
 */
final class PrimaryScanner implements ClientCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrimaryScanner.class);

    private final EventLoopGroup workerGroup =
            new NioEventLoopGroup(1, new DefaultThreadFactory("primary-scanner-worker", true));
    // Connecting to an unreachable registry blocks until the TCP connect times out, so it never runs on the scan
    private final ExecutorService connector =
            Executors.newCachedThreadPool(new DefaultThreadFactory("primary-scanner-connector", true));
    private final Map<String, Client> peers = new ConcurrentHashMap<>();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private final Supplier<PrimaryStanding> standing;
    private final Consumer<PrimaryStanding> onRivalStanding;

    /**
     * @param standing        this primary's current standing
     * @param onRivalStanding receives the standing another primary answered with
     */
    PrimaryScanner(Supplier<PrimaryStanding> standing, Consumer<PrimaryStanding> onRivalStanding) {
        this.standing = standing;
        this.onRivalStanding = onRivalStanding;
    }

    void scan() {
        for (String address : Constants.getRegistryLocations()) {
            if (address.equals(Constants.getMyAddress())) {
                continue;
            }
            Client client = peers.get(address);
            if (client != null && client.isConnected()) {
                send(client, new PrimaryRegistryLookupMessage());
            } else {
                if (client != null) {
                    peers.remove(address, client);
                }
                connectInBackground(address);
            }
        }
    }

    @Override
    public void onConnect(Client client) {
        LOGGER.info("Primary scanner reached registry at {}", client.getRemoteAddress());
        send(client, new PrimaryRegistryLookupMessage());
    }

    @Override
    public void onDisconnect(Client client) {
        LOGGER.info("Primary scanner lost registry at {}", client.getRemoteAddress());
    }

    @Override
    public void onMessage(Client client, Message message) {
        if (message instanceof PrimaryRegistryLookupResponseMessage lookup && lookup.getRank() == RANK_PRIMARY) {
            LOGGER.warn("Registry at {} is also primary, in term {}. Putting it to a vote",
                    client.getRemoteAddress(), lookup.getTerm());
            send(client, standing.get());
        } else if (message instanceof PrimaryStanding rival) {
            onRivalStanding.accept(rival);
        }
    }

    @Override
    public void onError(Client client, Throwable throwable) {
        LOGGER.error("Primary scanner error with {}", client.getRemoteAddress(), throwable);
    }

    private void connectInBackground(String address) {
        if (!connecting.add(address)) {
            return;
        }
        connector.execute(() -> {
            try {
                Client client = new Client(address, Constants.REGISTRY_PORT, this,
                        dev.heysulo.archon.registry.tls.RegistryTls.current().peerContext(), workerGroup);
                // Registries are trusted by the identity in their certificate, not by address
                client.setHostnameVerificationEnabled(false);
                client.settings().addTrustedPackage("dev.heysulo.archon.**");
                Constants.TRUSTED_TIME_CLASSES.forEach(client.settings()::addTrustedPackage);
                client.settings().setHeartbeatInterval(Constants.REGISTRY_HEARTBEAT_INTERVAL);
                client.connect();
                peers.put(address, client);
            } catch (Exception e) {
                LOGGER.debug("Primary scanner could not reach {}: {}", address, e.getMessage());
            } finally {
                connecting.remove(address);
            }
        });
    }

    private static void send(Client client, Message message) {
        try {
            client.send(message);
        } catch (RuntimeException e) {
            LOGGER.info("Primary scanner could not send to {}: {}", client.getRemoteAddress(), e.getMessage());
        }
    }
}
