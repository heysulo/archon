package dev.heysulo.archon.sdk.services;

import dev.heysulo.databridge.core.common.Message;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Matches the registry's responses to the requests waiting for them. */
public final class PendingRequests {
    /** Marks a message nobody waits for, such as a service announced again after reconnecting. */
    public static final long UNTRACKED = 0;

    private final AtomicLong nextId = new AtomicLong(UNTRACKED);
    private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

    public long open() {
        long id = nextId.incrementAndGet();
        pending.put(id, new CompletableFuture<>());
        return id;
    }

    /**
     * The request stays open until its waiter collects the response, which may arrive before the waiter waits.
     *
     * @return whether a request was waiting for this response
     */
    public boolean complete(long requestId, Message response) {
        CompletableFuture<Message> future = pending.get(requestId);
        return future != null && future.complete(response);
    }

    public <T extends Message> T await(long requestId, Class<T> type, long timeoutMillis) {
        CompletableFuture<Message> future = pending.get(requestId);
        if (future == null) {
            throw new IllegalArgumentException("No pending request " + requestId);
        }
        try {
            return type.cast(future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Interrupted while waiting for the registry", e);
        } catch (TimeoutException e) {
            throw new ServiceException("The registry did not answer within " + timeoutMillis + " ms", e);
        } catch (ExecutionException e) {
            throw new ServiceException("The registry request failed", e.getCause());
        } finally {
            pending.remove(requestId);
        }
    }
}
