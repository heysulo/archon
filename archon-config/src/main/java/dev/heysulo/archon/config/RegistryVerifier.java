package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.RegistryProtocol;
import dev.heysulo.archon.dictionary.sdk.messages.VerifyInstanceRequest;
import dev.heysulo.archon.dictionary.sdk.messages.VerifyInstanceResponse;
import dev.heysulo.archon.sdk.registry.RegistryLookupService;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asks the primary registry whether a caller's identity and registration token are genuine. Keeps one connection
 * and finds the primary again when it fails or stops answering, as after a registry failover. Blocks, so it never
 * runs on a network thread.
 */
final class RegistryVerifier implements Verifier, ClientCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryVerifier.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(3);
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 3_000;
    private static final long ANSWER_TIMEOUT_MILLIS = 3_000;

    private final EventLoopGroup eventLoops =
            new NioEventLoopGroup(1, new DefaultThreadFactory("config-registry", true));
    private final Map<Long, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong();
    private volatile Client client;
    private volatile CountDownLatch handshake = new CountDownLatch(1);

    @Override
    public Result verify(String group, String name, int rank, String token) {
        Client current;
        try {
            current = connection();
        } catch (IllegalStateException e) {
            LOGGER.warn("Cannot verify {}/{}: {}", group, name, e.getMessage());
            return Result.UNAVAILABLE;
        }
        long requestId = nextRequestId.incrementAndGet();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        pending.put(requestId, answer);
        try {
            current.send(new VerifyInstanceRequest(requestId, group, name, rank, token));
            return answer.get(ANSWER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ? Result.VALID : Result.INVALID;
        } catch (TimeoutException e) {
            // A registry that is no longer primary drops the request; find the new one next time
            drop(current);
            return Result.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        } catch (Exception e) {
            drop(current);
            return Result.UNAVAILABLE;
        } finally {
            pending.remove(requestId);
        }
    }

    private synchronized Client connection() {
        Client current = client;
        if (current != null && current.isConnected()) {
            return current;
        }
        String primary = RegistryLookupService.getInstance().search();
        if (primary == null) {
            throw new IllegalStateException("No primary registry is answering");
        }
        handshake = new CountDownLatch(1);
        Client fresh = new Client(primary, RegistryProtocol.PORT, this, eventLoops);
        fresh.settings().setConnectTimeout(CONNECT_TIMEOUT);
        fresh.settings().setHeartbeatInterval(HEARTBEAT_INTERVAL);
        try {
            fresh.connect();
            if (!handshake.await(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("The registry at " + primary + " did not complete the handshake");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } catch (IllegalStateException e) {
            drop(fresh);
            throw e;
        } catch (Exception e) {
            // Netty rethrows checked connect failures without declaring them
            drop(fresh);
            throw new IllegalStateException("Cannot connect to the registry at " + primary + ": " + e.getMessage());
        }
        client = fresh;
        return fresh;
    }

    private synchronized void drop(Client failed) {
        if (client == failed) {
            client = null;
        }
        try {
            failed.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onConnect(Client connected) {
        handshake.countDown();
    }

    @Override
    public void onDisconnect(Client disconnected) {
        if (client == disconnected) {
            client = null;
        }
    }

    @Override
    public void onMessage(Client receiver, Message message) {
        if (message instanceof VerifyInstanceResponse response) {
            CompletableFuture<Boolean> answer = pending.get(response.getRequestId());
            if (answer != null) {
                answer.complete(response.isValid());
            }
        }
    }

    @Override
    public void onError(Client failed, Throwable cause) {
        LOGGER.warn("Registry connection error: {}", cause.getMessage());
    }
}
