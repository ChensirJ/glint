package io.github.glint.diag;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.MimeTypes;
import io.github.glint.obs.PinningWatcher;
import io.github.glint.obs.SlowLog;
import io.github.glint.obs.Trace;
import io.github.glint.router.Router;
import io.github.glint.util.Json;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers JSON and optional dashboard routes under a diagnostics prefix
 * such as {@code /_vt}.
 */
public final class DiagnosticsHandler {

    private DiagnosticsHandler() {
    }

    public static void install(Router router, String prefix) {
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;

        router.add(HttpMethod.GET, base, (req, resp) -> serveDashboard(resp, "/index.html"));
        router.add(HttpMethod.GET, base + "/", (req, resp) -> serveDashboard(resp, "/index.html"));
        router.add(HttpMethod.GET, base + "/index.html", (req, resp) -> serveDashboard(resp, "/index.html"));
        router.add(HttpMethod.GET, base + "/app.js", (req, resp) -> serveDashboard(resp, "/app.js"));
        router.add(HttpMethod.GET, base + "/styles.css", (req, resp) -> serveDashboard(resp, "/styles.css"));

        router.add(HttpMethod.GET, base + "/api/metrics", (req, resp) ->
                resp.json(Json.stringify(Trace.METRICS.snapshot())));

        router.add(HttpMethod.GET, base + "/api/health", (req, resp) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "up");
            m.put("server", "Glint/0.1");
            m.put("java", System.getProperty("java.version"));
            m.put("activeRequests", Trace.activeRequests());
            resp.json(Json.stringify(m));
        });

        router.add(HttpMethod.GET, base + "/api/threads", (req, resp) -> {
            long vtEstimate = Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isVirtual)
                    .count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("virtualThreadsApprox", vtEstimate);
            m.put("activeRequests", Trace.activeRequests());
            m.put("pinningEvents", Trace.METRICS.pinningCount());
            resp.json(Json.stringify(m));
        });

        router.add(HttpMethod.GET, base + "/api/slow", (req, resp) -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (SlowLog.SlowRequest s : SlowLog.recent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("path", s.path());
                m.put("durationMs", s.durationMs());
                m.put("epochMs", s.epochMs());
                m.put("spans", s.spans());
                items.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("items", items);
            resp.json(Json.stringify(root));
        });

        router.add(HttpMethod.GET, base + "/api/pinning", (req, resp) -> {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("count", Trace.METRICS.pinningCount());
            root.put("events", PinningWatcher.recentAsMaps());
            resp.json(Json.stringify(root));
        });
    }

    private static void serveDashboard(io.github.glint.http.HttpResponse resp, String resource)
            throws Exception {
        String classpath = "/glint/dashboard" + resource;
        InputStream stream = DiagnosticsHandler.class.getResourceAsStream(classpath);
        if (stream == null) {
            // Also try context classloader (dashboard jar on classpath).
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                stream = cl.getResourceAsStream("glint/dashboard" + resource);
            }
        }
        if (stream == null) {
            if (resource.endsWith(".html") || resource.equals("/index.html")) {
                resp.html(minimalHtml());
            } else {
                resp.status(404).text("Dashboard asset not found: " + resource
                        + " (add glint-dashboard to the classpath)");
            }
            return;
        }
        try (InputStream in = stream) {
            byte[] data = in.readAllBytes();
            resp.bytes(data, MimeTypes.forPath(resource));
        }
    }

    private static String minimalHtml() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<title>Glint Diagnostics</title></head><body>"
                + "<h1>Glint Diagnostics</h1>"
                + "<p>Dashboard UI not on classpath. JSON APIs still work:</p>"
                + "<ul>"
                + "<li><a href=\"api/metrics\">api/metrics</a></li>"
                + "<li><a href=\"api/health\">api/health</a></li>"
                + "<li><a href=\"api/threads\">api/threads</a></li>"
                + "<li><a href=\"api/slow\">api/slow</a></li>"
                + "<li><a href=\"api/pinning\">api/pinning</a></li>"
                + "</ul></body></html>";
    }
}
