package io.github.glint.middleware;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Per-key token-bucket rate limiter (thread-safe, lock-free refill math).
 *
 * <p>Suitable for middleware: each virtual-thread request calls {@link #tryAcquire(Object)}.
 */
public final class TokenBucketRateLimiter {

    private final double capacity;
    private final double refillPerNanos;
    private final ConcurrentHashMap<Object, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param permitsPerSecond sustained refill rate
     * @param burst            max tokens (bucket capacity)
     */
    public TokenBucketRateLimiter(double permitsPerSecond, int burst) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be > 0");
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("burst must be > 0");
        }
        this.capacity = burst;
        this.refillPerNanos = permitsPerSecond / 1_000_000_000.0;
    }

    /** Try to consume one token for {@code key}. */
    public boolean tryAcquire(Object key) {
        Objects.requireNonNull(key, "key");
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return b.tryAcquire(capacity, refillPerNanos);
    }

    /** Approximate number of tracked keys (for tests / diagnostics). */
    public int keyCount() {
        return buckets.size();
    }

    private static final class Bucket {
        private final AtomicLong tokensMicros;
        private final AtomicLong lastNanos;

        Bucket(double capacity) {
            this.tokensMicros = new AtomicLong((long) (capacity * 1_000_000L));
            this.lastNanos = new AtomicLong(System.nanoTime());
            }

        boolean tryAcquire(double capacity, double refillPerNanos) {
            while (true) {
                long now = System.nanoTime();
                long prevNanos = lastNanos.get();
                long elapsed = Math.max(0L, now - prevNanos);
                long prevTokens = tokensMicros.get();
                double tokens = prevTokens / 1_000_000.0;
                tokens = Math.min(capacity, tokens + elapsed * refillPerNanos);
                if (tokens < 1.0) {
                    // Still update timestamp so refill progresses.
                    lastNanos.compareAndSet(prevNanos, now);
                    tokensMicros.compareAndSet(prevTokens, (long) (tokens * 1_000_000.0));
                    return false;
                }
                double next = tokens - 1.0;
                long nextMicros = (long) (next * 1_000_000.0);
                if (tokensMicros.compareAndSet(prevTokens, nextMicros)) {
                    lastNanos.compareAndSet(prevNanos, now);
                    return true;
                }
            }
        }
    }

    /** Default key extractor: client IP from {@code X-Forwarded-For} or remote hint header, else {@code global}. */
    public static Function<io.github.glint.http.HttpRequest, Object> clientIpKey() {
        return req -> req.header("x-forwarded-for")
                .map(v -> v.split(",")[0].trim())
                .filter(s -> !s.isEmpty())
                .or(() -> req.header("x-real-ip"))
                .orElse("global");
    }
}