package io.github.glint.obs;

import io.github.glint.ThrowingRunnable;
import io.github.glint.http.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-request tracing context for JDK 21.
 *
 * <p>Uses {@link ThreadLocal} (not {@code ScopedValue}) so the project stays
 * on stable JDK 21 APIs without {@code --enable-preview}.
 * Always {@link ThreadLocal#remove()} after the request so keep-alive
 * connections that reuse the same virtual thread do not leak context.
 *
 * <p>After each request, optional {@link ObsExporter} SPI implementations
 * receive the metrics snapshot and request context. The snapshot is built
 * only when an exporter is registered, so a keep-alive handler with no
 * exporter does not allocate the diagnostics maps.
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

    private static final LongAdder ACTIVE = new LongAdder();
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private Trace() {
    }

    public static void noteActive(int delta) {
        ACTIVE.add(delta);
    }

    public static int activeRequests() {
        return (int) ACTIVE.sum();
    }

    /** Run {@code body} with a fresh request context and record latency metrics. */
    public static void run(HttpRequest req, ThrowingRunnable body) throws Exception {
        long start = System.nanoTime();
        Ctx ctx = new Ctx(
                Long.toHexString(NEXT_ID.incrementAndGet()),
                req.path(),
                start,
                new ArrayList<>(2));
        long tid = Thread.currentThread().threadId();
        boolean pins = PinningWatcher.isStarted();
        CURRENT.set(ctx);
        if (pins) {
            BY_THREAD.put(tid, ctx);
        }
        try {
            body.run();
        } finally {
            CURRENT.remove();
            if (pins) {
                BY_THREAD.remove(tid);
            }
            long dur = System.nanoTime() - start;
            METRICS.record(req.path(), dur);
            if (dur > 500_000_000L) {
                SlowLog.dump(ctx, dur);
            }
            // Exporters are optional. Building the snapshot on every tiny request
            // allocated several maps and walked the histogram under no listener.
            if (ObsExporters.hasAny()) {
                ObsExporters.emitRequest(ctx, dur);
                ObsExporters.emitMetrics(METRICS.snapshot());
            }
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
