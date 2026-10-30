package io.github.glint.obs;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtlpJsonTest {

    @Test
    void buildsResourceMetricsJson() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("count", 3L);
        path.put("p50Ms", 1.0);
        path.put("p99Ms", 5.0);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("totalRequests", 3L);
        snap.put("pinningEvents", 0L);
        snap.put("activeRequests", 0);
        snap.put("paths", Map.of("/hello", path));

        Map<String, Object> export = OtlpJson.metricsExport(snap);
        assertTrue(export.containsKey("resourceMetrics"));
        @SuppressWarnings("unchecked")
        List<?> rm = (List<?>) export.get("resourceMetrics");
        assertFalse(rm.isEmpty());

        String json = OtlpJson.metricsExportJson(snap);
        assertTrue(json.contains("glint.requests.total"));
        assertTrue(json.contains("http.route"));
        assertTrue(json.contains("/hello"));
    }
}
