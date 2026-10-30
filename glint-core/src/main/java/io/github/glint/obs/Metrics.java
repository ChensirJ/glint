package io.github.glint.obs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lock-friendly latency metrics.
 * Counts use {@link LongAdder}; latency histograms use {@link ReentrantLock}
 * instead of {@code synchronized} to avoid pinning virtual threads on JDK 21.
 */
public final class Metrics {

    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> latency = new ConcurrentHashMap<>();
    private final LongAdder pinningEvents = new LongAdder();

    public void record(String path, long durNs) {
        counts.computeIfAbsent(path, k -> new LongAdder()).increment();
        latency.computeIfAbsent(path, k -> new Histogram()).add(durNs);
    }

    public void recordPinning() {
        pinningEvents.increment();
    }

    public long pinningCount() {
        return pinningEvents.sum();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pinningEvents", pinningEvents.sum());
        Map<String, Object> paths = new LinkedHashMap<>();
        for (String path : counts.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", counts.get(path).sum());
            Histogram h = latency.get(path);
            if (h != null) {
                m.put("p50Ms", h.percentileMs(0.50));
                m.put("p99Ms", h.percentileMs(0.99));
            }
            paths.put(path, m);
        }
        root.put("paths", paths);
        return root;
    }

    /** Power-of-two latency buckets for approximate percentiles. */
    public static final class Histogram {
        private final ReentrantLock lock = new ReentrantLock();
        private final long[] buckets = new long[64];
        private long total;

        public void add(long ns) {
            int i = Math.min(63, 63 - Long.numberOfLeadingZeros(Math.max(1L, ns)));
            lock.lock();
            try {
                buckets[i]++;
                total++;
            } finally {
                lock.unlock();
            }
        }

        public double percentileMs(double p) {
            lock.lock();
            try {
                if (total == 0) {
                    return 0.0;
                }
                long target = Math.max(1L, (long) Math.ceil(total * p));
                long acc = 0;
                for (int i = 0; i < buckets.length; i++) {
                    acc += buckets[i];
                    if (acc >= target) {
                        // Bucket i covers roughly [2^i, 2^(i+1)) nanoseconds.
                        long midNs = 1L << Math.min(62, i);
                        return midNs / 1_000_000.0;
                    }
                }
                return (1L << 62) / 1_000_000.0;
            } finally {
                lock.unlock();
            }
        }

        public long total() {
            lock.lock();
            try {
                return total;
            } finally {
                lock.unlock();
            }
        }
    }
}
