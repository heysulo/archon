package dev.heysulo.archon.portal.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Slows down password guessing for an account. After {@value #FREE_ATTEMPTS} failures in a row, each further
 * attempt must wait twice as long as the last, up to five minutes. Held in memory per portal replica, which is
 * enough to make guessing impractically slow.
 */
public final class LoginThrottle {
    static final int FREE_ATTEMPTS = 5;
    private static final Duration MAX_WAIT = Duration.ofMinutes(5);
    private static final int MAX_TRACKED = 10_000;

    private record Failures(int count, Instant blockedUntil) {
    }

    private final Clock clock;
    // Oldest first, so the map stays bounded however many usernames are tried
    private final Map<String, Failures> failures = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Failures> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** How long the caller must still wait before trying this account again; zero if it may try now. */
    public synchronized Duration waitFor(String key) {
        Failures current = failures.get(key);
        if (current == null || current.blockedUntil() == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), current.blockedUntil());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public synchronized void failed(String key) {
        Failures current = failures.getOrDefault(key, new Failures(0, null));
        int count = current.count() + 1;
        Instant blockedUntil = null;
        if (count >= FREE_ATTEMPTS) {
            long seconds = 1L << Math.min(count - FREE_ATTEMPTS, 20);
            Duration wait = Duration.ofSeconds(seconds);
            blockedUntil = clock.instant().plus(wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait);
        }
        failures.put(key, new Failures(count, blockedUntil));
    }

    public synchronized void succeeded(String key) {
        failures.remove(key);
    }
}
