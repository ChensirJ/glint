package io.github.glint.obs;

import io.github.glint.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a minimal OTLP/HTTP JSON metrics payload from {@link Metrics#snapshot()}.
 *
 * <p>Compatible in spirit with the OpenTelemetry metrics protobuf JSON mapping
 * so scrapers or collectors that accept OTLP/HTTP JSON can ingest it.
 * No OpenTelemetry SDK dependency is required in core.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/otlp/#otlphttp">OTLP/HTTP</a>
 */
public final class OtlpJson {

    private OtlpJson() {
    }

    /**
     * Convert a Glint metrics snapshot into an OTLP ExportMetricsServiceRequest-like
     * JSON object (resourceMetrics array).
     */
    public static Map<String, Object> metricsExport(Map<String, Object> snapshot) {
        long nowNano = System.currentTimeMillis() * 1_000_000L;

        List<Map<String, Object>> scopeMetricsData = new ArrayList<>();

        // Counter: glint.requests.total
        scopeMetricsData.add(sumMetric("glint.requests.total",
                "Total HTTP requests handled",
                numberDataPoint(nowNano, asLong(snapshot.get("totalRequests")))));

        // Counter: glint.pinning.events
        scopeMetricsData.add(sumMetric("glint.pinning.events",
                "Virtual-thread pinning events observed via JFR",
                numberDataPoint(nowNano, asLong(snapshot.get("pinningEvents")))));

        // Gauge: glint.requests.active
        scopeMetricsData.add(gaugeMetric("glint.requests.active",
                "In-flight HTTP requests",
                numberDataPoint(nowNano, asLong(snapshot.get("activeRequests")))));

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = snapshot.get("paths") instanceof Map
                ? (Map<String, Object>) snapshot.get("paths")
                : Map.of();

        List<Map<String, Object>> pathCountPoints = new ArrayList<>();
        List<Map<String, Object>> p99Points = new ArrayList<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> pm)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pathMetrics = (Map<String, Object>) pm;
            Map<String, Object> attrs = Map.of(
                    "key", "http.route",
                    "value", Map.of("stringValue", e.getKey()));
            Map<String, Object> countPt = numberDataPoint(nowNano, asLong(pathMetrics.get("count")));
            countPt.put("attributes", List.of(attrs));
            pathCountPoints.add(countPt);

            Map<String, Object> p99Pt = numberDataPointDouble(nowNano, asDouble(pathMetrics.get("p99Ms")));
            p99Pt.put("attributes", List.of(attrs));
            p99Points.add(p99Pt);
        }
        if (!pathCountPoints.isEmpty()) {
            scopeMetricsData.add(sumMetricMulti("glint.route.requests",
                    "Request count by route", pathCountPoints));
            scopeMetricsData.add(gaugeMetricMulti("glint.route.p99_ms",
                    "Approximate P99 latency in milliseconds by route", p99Points));
        }

        Map<String, Object> scopeMetrics = new LinkedHashMap<>();
        scopeMetrics.put("scope", Map.of("name", "glint", "version", "0.1.0"));
        scopeMetrics.put("metrics", scopeMetricsData);

        Map<String, Object> resourceMetrics = new LinkedHashMap<>();
        resourceMetrics.put("resource", Map.of(
                "attributes", List.of(
                        Map.of("key", "service.name",
                                "value", Map.of("stringValue", "glint")))));
        resourceMetrics.put("scopeMetrics", List.of(scopeMetrics));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("resourceMetrics", List.of(resourceMetrics));
        return root;
    }

    /** Serialize {@link #metricsExport(Map)} to a JSON string. */
    public static String metricsExportJson(Map<String, Object> snapshot) {
        return Json.stringify(metricsExport(snapshot));
    }

    private static Map<String, Object> sumMetric(String name, String desc, Map<String, Object> point) {
        return sumMetricMulti(name, desc, List.of(point));
    }

    private static Map<String, Object> sumMetricMulti(String name, String desc, List<Map<String, Object>> points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", desc);
        m.put("unit", "1");
        m.put("sum", Map.of(
                "aggregationTemporality", 2, // CUMULATIVE
                "isMonotonic", true,
                "dataPoints", points));
        return m;
    }

    private static Map<String, Object> gaugeMetric(String name, String desc, Map<String, Object> point) {
        return gaugeMetricMulti(name, desc, List.of(point));
    }

    private static Map<String, Object> gaugeMetricMulti(String name, String desc, List<Map<String, Object>> points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", desc);
        m.put("unit", name.endsWith("_ms") ? "ms" : "1");
        m.put("gauge", Map.of("dataPoints", points));
        return m;
    }

    private static Map<String, Object> numberDataPoint(long timeNano, long value) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("timeUnixNano", Long.toString(timeNano));
        pt.put("asInt", Long.toString(value));
        return pt;
    }

    private static Map<String, Object> numberDataPointDouble(long timeNano, double value) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("timeUnixNano", Long.toString(timeNano));
        pt.put("asDouble", value);
        return pt;
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private static double asDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }
}
