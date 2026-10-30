package io.github.glint.obs;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    @Test
    void recordsCountsAndPercentiles() {
        Metrics metrics = new Metrics();
        for (int i = 0; i < 100; i++) {
            metrics.record("/api", 1_000_000L); // 1ms
        }
        metrics.record("/api", 50_000_000L); // 50ms outlier

        Map<String, Object> snap = metrics.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) snap.get("paths");
        @SuppressWarnings("unchecked")
        Map<String, Object> api = (Map<String, Object>) paths.get("/api");

        assertEquals(101L, api.get("count"));
        assertTrue(((Number) api.get("p50Ms")).doubleValue() >= 0.0);
        assertTrue(((Number) api.get("p99Ms")).doubleValue() >= 0.0);
    }

    @Test
    void pinningCounterIncrements() {
        Metrics metrics = new Metrics();
        assertEquals(0, metrics.pinningCount());
        metrics.recordPinning();
        metrics.recordPinning();
        assertEquals(2, metrics.pinningCount());
    }

    @Test
    void histogramEmptyPercentileIsZero() {
        Metrics.Histogram h = new Metrics.Histogram();
        assertEquals(0.0, h.percentileMs(0.99));
    }
}
