package io.github.glint.obs;

import io.github.glint.ThrowingRunnable;
import io.github.glint.http.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-request tracing context for JDK 21.
 *
 * <p>Uses {@link ThreadLocal} (not {@code ScopedValue}) so the project stays
 * on stable JDK 21 APIs without {@code --enable-preview}.
 * Always {@link ThreadLocal#remove()} after the request so keep-alive
 * connections that reuse the same virtual thread do not leak context.
 *
 * <p>After each request, optional {@link ObsExporter} SPI implementations
 * receive the metrics snapshot and request context.
 */
public final class Trace {

    /** One timing span inside a request. */
    public record Span(String name, long startNs, long durNs) {
    }

    /** Request-scoped context held in a ThreadLocal. */
    public record Ctx(String id, String path, long startNs, List<Span> spans) {
    }

    static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    /** Maps virtual thread id → context for the pinning watcher. */
    static final Map<Long, Ctx> BY_THREAD = new ConcurrentHashMap<>();

    public static final Metrics METRICS = new Metrics();

    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private Trace() {
    }

    public static void noteActive(int delta) {
        ACTIVE.addAndGet(delta);
    }

    public static int activeRequests() {
        return ACTIVE.get();
    }

    /** Run {@code body} with a fresh request context and record latency metrics. */
    public static void run(HttpRequest req, ThrowingRunnable body) throws Exception {
        Ctx ctx = new Ctx(
                UUID.randomUUID().toString(),
                req.path(),
                System.nanoTime(),
                new ArrayList<>());
        long tid = Thread.currentThread().threadId();
        CURRENT.set(ctx);
        BY_THREAD.put(tid, ctx);
        try {
            body.run();
        } finally {
            CURRENT.remove();
            BY_THREAD.remove(tid);
            long dur = System.nanoTime() - ctx.startNs();
            METRICS.record(req.path(), dur);
            if (dur > 500_000_000L) {
                SlowLog.dump(ctx, dur);
            }
            ObsExporters.emitRequest(ctx, dur);
            // Cheap snapshot fan-out for metric-only exporters (sampled every request;
            // exporters should be non-blocking).
            ObsExporters.emitMetrics(METRICS.snapshot());
        }
    }

    /** Record a named sub-span around a blocking section (DB, HTTP client, etc.). */
    public static <T> T span(String name, Callable<T> callable) throws Exception {
        long t = System.nanoTime();
        try {
            return callable.call();
        } finally {
            Ctx ctx = CURRENT.get();
            if (ctx != null) {
                ctx.spans().add(new Span(name, t, System.nanoTime() - t));
            }
        }
    }

    public static void span(String name, ThrowingRunnable runnable) throws Exception {
        span(name, () -> {
            runnable.run();
            return null;
        });
    }

    public static Ctx current() {
        return CURRENT.get();
    }

    public static Ctx forThread(long threadId) {
        return BY_THREAD.get(threadId);
    }
}
