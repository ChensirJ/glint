package io.github.glint.middleware;

import io.github.glint.http.HttpResponse;
import io.github.glint.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in middleware helpers: CORS, request logging, and JSON exception mapping.
 */
public final class Middlewares {

    private Middlewares() {
    }

    /**
     * Permissive CORS for demos. Sets ACAO / ACAM / ACAH and short-circuits OPTIONS.
     */
    public static Middleware cors() {
        return cors("*");
    }

    public static Middleware cors(String allowOrigin) {
        return (req, resp, next) -> {
            resp.header("Access-Control-Allow-Origin", allowOrigin);
            resp.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS");
            resp.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
            resp.header("Access-Control-Max-Age", "86400");
            if (req.method().name().equals("OPTIONS")) {
                resp.status(204).empty();
                return;
            }
            next.handle(req, resp);
        };
    }

    /** Simple access log to stdout: METHOD path -> status (ms). */
    public static Middleware requestLogging() {
        return (req, resp, next) -> {
            long t = System.nanoTime();
            try {
                next.handle(req, resp);
            } finally {
                System.out.printf("%s %s -> %d (%.1fms)%n",
                        req.method(),
                        req.rawTarget(),
                        resp.status(),
                        (System.nanoTime() - t) / 1_000_000.0);
            }
        };
    }

    /**
     * Catch handler exceptions and emit {@code {"error":"...","status":500}} unless
     * the response was already committed.
     */
    public static Middleware exceptionHandler() {
        return (req, resp, next) -> {
            try {
                next.handle(req, resp);
            } catch (Exception ex) {
                if (!resp.committed()) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    body.put("status", 500);
                    resp.status(500).json(Json.stringify(body));
                } else {
                    throw ex;
                }
            }
        };
    }
}
