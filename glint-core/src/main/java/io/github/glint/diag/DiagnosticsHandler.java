package io.github.glint.diag;

import io.github.glint.http.HttpMethod;
import io.github.glint.obs.Trace;
import io.github.glint.router.Router;

import java.io.InputStream;
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

        router.add(HttpMethod.GET, base + "/api/metrics", (req, resp) -> {
            resp.json(toJson(Trace.METRICS.snapshot()));
        });

        router.add(HttpMethod.GET, base + "/api/health", (req, resp) -> {
            StringBuilder json = new StringBuilder();
            json.append('{');
            json.append("\"status\":\"up\",");
            json.append("\"server\":\"Glint/0.1\",");
            json.append("\"java\":\"").append(System.getProperty("java.version")).append("\"");
            json.append('}');
            resp.json(json.toString());
        });

        router.add(HttpMethod.GET, base + "/api/threads", (req, resp) -> {
            long vtEstimate = Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isVirtual)
                    .count();
            StringBuilder json = new StringBuilder();
            json.append('{');
            json.append("\"virtualThreadsApprox\":").append(vtEstimate).append(',');
            json.append("\"pinningEvents\":").append(Trace.METRICS.pinningCount());
            json.append('}');
            resp.json(json.toString());
        });
    }

    private static void serveDashboard(io.github.glint.http.HttpResponse resp, String resource)
            throws Exception {
        String classpath = "/glint/dashboard" + resource;
        InputStream in = DiagnosticsHandler.class.getResourceAsStream(classpath);
        if (in == null) {
            if (resource.endsWith(".html") || resource.equals("/index.html")) {
                resp.html(minimalHtml());
            } else {
                resp.status(404).text("Dashboard asset not found: " + resource
                        + " (add glint-dashboard to the classpath)");
            }
            return;
        }
        try (in) {
            byte[] data = in.readAllBytes();
            resp.bytes(data, contentType(resource));
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "text/html; charset=utf-8";
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
                + "</ul></body></html>";
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof String s) {
            return "\"" + escape(s) + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append('"')
                        .append(':').append(toJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 92) { // backslash
                out.append((char) 92).append((char) 92);
            } else if (c == 34) { // quote
                out.append((char) 92).append((char) 34);
            } else if (c == 10) {
                out.append((char) 92).append('n');
            } else if (c == 13) {
                out.append((char) 92).append('r');
            } else if (c == 9) {
                out.append((char) 92).append('t');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
