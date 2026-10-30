package io.github.glint.examples;

import io.github.glint.Glint;
import io.github.glint.obs.Trace;

/**
 * Standalone Glint demo — no Spring required.
 *
 * <pre>{@code
 * mvn -pl glint-examples -am exec:java \
 *   -Dexec.mainClass=io.github.glint.examples.StandaloneDemo
 * }</pre>
 *
 * Then open http://127.0.0.1:8080/_vt for the diagnostics dashboard.
 */
public final class StandaloneDemo {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Glint.create(port)
                .before((req, resp, next) -> {
                    long t = System.nanoTime();
                    next.handle(req, resp);
                    System.out.printf("%s %s -> %d (%.1fms)%n",
                            req.method(), req.path(), resp.status(),
                            (System.nanoTime() - t) / 1_000_000.0);
                })
                .get("/hello", (req, resp) -> resp.text("Hello, virtual threads!"))
                .get("/users/{id}", (req, resp) -> {
                    String id = req.pathParam("id");
                    String payload = Trace.span("db.findUser", () -> {
                        Thread.sleep(25);
                        return jsonUser(id, "Ada");
                    });
                    resp.json(payload);
                })
                .post("/echo", (req, resp) -> resp.json(jsonEcho(req.bodyText())))
                .diagnostics("/_vt")
                .startAsync();

        System.out.println("Glint standalone listening on http://127.0.0.1:" + port);
        System.out.println("Diagnostics: http://127.0.0.1:" + port + "/_vt");
    }

    private static String jsonUser(String id, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append('"').append("id").append('"').append(':').append('"').append(id).append('"').append(',');
        sb.append('"').append("name").append('"').append(':').append('"').append(name).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String jsonEcho(String body) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append('"').append("echo").append('"').append(':').append('"').append(escape(body)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 92 || c == '"') {
                out.append((char) 92);
            }
            out.append(c);
        }
        return out.toString();
    }
}
