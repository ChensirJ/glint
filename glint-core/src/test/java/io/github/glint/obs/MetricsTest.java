package io.github.glint.obs;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    @Test
    void recordsCountsAndPercentiles() {
        Metrics m = new Metrics();
        for (int i = 0; i < 100; i++) {
            m.record("/x", 1_000_000L); // 1ms
        }
        m.recordPinning();
        m.recordRateLimit(true);
        m.recordRateLimit(true);
        m.recordRateLimit(false);
        Map<String, Object> snap = m.snapshot();
        assertEquals(1L, snap.get("pinningEvents"));
        assertEquals(100L, snap.get("totalRequests"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rl = (Map<String, Object>) snap.get("rateLimit");
        assertEquals(2L, ((Number) rl.get("allowed")).longValue());
        assertEquals(1L, ((Number) rl.get("rejected")).longValue());
        assertEquals(3L, ((Number) rl.get("total")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) snap.get("paths");
        @SuppressWarnings("unchecked")
        Map<String, Object> x = (Map<String, Object>) paths.get("/x");
        assertEquals(100L, x.get("count"));
        assertTrue(((Number) x.get("p50Ms")).doubleValue() >= 0);
    }
}
