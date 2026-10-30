package io.github.glint.examples;

import io.github.glint.Glint;
import io.github.glint.middleware.Middlewares;
import io.github.glint.obs.Trace;

/**
 * Standalone Glint demo - no Spring required.
 *
 * <pre>{@code
 * mvn -pl glint-examples -am exec:java
 * # or
 * .\scripts\run-standalone.ps1
 * }</pre>
 *
 * Then open http://127.0.0.1:8080/_vt for the diagnostics dashboard.
 */
public final class StandaloneDemo {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Glint.create(port)
                .before(Middlewares.requestLogging())
                .before(Middlewares.exceptionHandler())
                .before(Middlewares.cors())
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
                .put("/item", (req, resp) -> resp.status(201).json("{\"ok\":true}"))
                .delete("/item", (req, resp) -> resp.status(204).empty())
                .options("/hello", (req, resp) -> resp.status(204).empty())
                .diagnostics("/_vt")
                .startAsync();

        System.out.println("Glint standalone listening on http://127.0.0.1:" + port);
        System.out.println("Diagnostics: http://127.0.0.1:" + port + "/_vt");
        Thread.currentThread().join();
    }

    private static String jsonUser(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}";
    }

    private static String jsonEcho(String body) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return "{\"echo\":\"" + out + "\"}";
    }
}
