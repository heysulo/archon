package dev.heysulo.archon.sdk.services;

import dev.heysulo.databridge.core.common.Message;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Matches responses to the requests waiting for them, by request id: the registry's answers to service requests, and
 * archon-config's to configuration requests. Thread safe.
 *
 * <p>A caller {@linkplain #open() opens} a request, sends it with the id, then {@linkplain #await waits}; the
 * network thread {@linkplain #complete completes} it when the response arrives, which may happen before the caller
 * starts waiting.
 */
public final class PendingRequests {
    /** Marks a message nobody waits for, such as a service announced again after reconnecting. */
    public static final long UNTRACKED = 0;

    private final AtomicLong nextId = new AtomicLong(UNTRACKED);
    private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

    /**
     * Opens a request.
     *
     * @return its id, to send with the request; never {@link #UNTRACKED}
     */
    public long open() {
        long id = nextId.incrementAndGet();
        pending.put(id, new CompletableFuture<>());
        return id;
    }

    /**
     * Hands a response to its request. The request stays open until its waiter collects the response, which may
     * arrive before the waiter waits.
     *
     * @param requestId the id the response carries
     * @param response  the response
     * @return whether a request was waiting for this response; false for {@link #UNTRACKED}, an unknown or timed-out
     *         id, or a second response
     */
    public boolean complete(long requestId, Message response) {
        CompletableFuture<Message> future = pending.get(requestId);
        return future != null && future.complete(response);
    }

    /**
     * Forgets a request whose message could not be sent.
     *
     * @param requestId the request's id
     */
    public void cancel(long requestId) {
        pending.remove(requestId);
    }

    /**
     * Waits for a request's response, then forgets the request.
     *
     * @param requestId     the id from {@link #open()}
     * @param type          the response's expected type
     * @param timeoutMillis how long to wait
     * @param <T>           the response's type
     * @return the response
     * @throws IllegalArgumentException if no request with this id is open
     * @throws ClassCastException       if the response is of another type
     * @throws ServiceException         if interrupted, or no response arrives in time
     */
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
