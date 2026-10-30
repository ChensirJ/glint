package io.github.glint.middleware;

import io.github.glint.http.HttpRequest;
import io.github.glint.util.Json;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Built-in middleware helpers: CORS, access log, token-bucket rate limit,
 * and JSON exception mapping.
 */
public final class Middlewares {

    private Middlewares() {
    }

    /** Permissive CORS for demos ({@code *} origin, short-circuit OPTIONS). */
    public static Middleware cors() {
        return cors(CorsOptions.permissive());
    }

    /** CORS with a fixed {@code Access-Control-Allow-Origin} value. */
    public static Middleware cors(String allowOrigin) {
        return cors(CorsOptions.builder().allowOrigin(allowOrigin).build());
    }

    /** CORS using a full {@link CorsOptions} configuration. */
    public static Middleware cors(CorsOptions options) {
        Objects.requireNonNull(options, "options");
        String methods = String.join(",", options.allowMethods());
        String headers = String.join(",", options.allowHeaders());
        String expose = options.exposeHeaders().isEmpty()
                ? null
                : String.join(",", options.exposeHeaders());
        return (req, resp, next) -> {
            resp.header("Access-Control-Allow-Origin", options.allowOrigin());
            resp.header("Access-Control-Allow-Methods", methods);
            resp.header("Access-Control-Allow-Headers", headers);
            resp.header("Access-Control-Max-Age", Integer.toString(options.maxAgeSeconds()));
            if (options.allowCredentials()) {
                resp.header("Access-Control-Allow-Credentials", "true");
            }
            if (expose != null) {
                resp.header("Access-Control-Expose-Headers", expose);
            }
            if (options.shortCircuitOptions() && "OPTIONS".equals(req.method().name())) {
                resp.status(204).empty();
                return;
            }
            next.handle(req, resp);
        };
    }

    /**
     * Access log to stdout: {@code METHOD path status durationMs}.
     * Alias of {@link #accessLog()}.
     */
    public static Middleware requestLogging() {
        return accessLog();
    }

    /** Access log to stdout. */
    public static Middleware accessLog() {
        return accessLog(System.out);
    }

    /** Access log written as a single line to {@code out}. */
    public static Middleware accessLog(PrintStream out) {
        Objects.requireNonNull(out, "out");
        return accessLog(line -> out.println(line));
    }

    /**
     * Access log with a custom sink (one formatted line per request).
     * Format: {@code METHOD path -> status (ms)}.
     */
    public static Middleware accessLog(Consumer<String> sink) {
        Objects.requireNonNull(sink, "sink");
        return (req, resp, next) -> {
            long t = System.nanoTime();
            try {
                next.handle(req, resp);
            } finally {
                double ms = (System.nanoTime() - t) / 1_000_000.0;
                sink.accept(String.format("%s %s -> %d (%.1fms)",
                        req.method(),
                        req.rawTarget(),
                        resp.status(),
                        ms));
            }
        };
    }

    /**
     * Token-bucket rate limit. When exhausted, responds {@code 429} with
     * {@code Retry-After: 1} and a small JSON body.
     *
     * @param permitsPerSecond sustained rate
     * @param burst            bucket capacity
     */
    public static Middleware rateLimit(double permitsPerSecond, int burst) {
        return rateLimit(permitsPerSecond, burst, TokenBucketRateLimiter.clientIpKey());
    }

    /**
     * Token-bucket rate limit with a custom key extractor
     * (for example per-user or per-API-key).
     */
    public static Middleware rateLimit(double permitsPerSecond,
                                       int burst,
                                       Function<HttpRequest, Object> keyFn) {
        Objects.requireNonNull(keyFn, "keyFn");
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(permitsPerSecond, burst);
        return (req, resp, next) -> {
            Object key = keyFn.apply(req);
            if (key == null) {
                key = "global";
            }
            if (!limiter.tryAcquire(key)) {
                resp.status(429)
                        .header("Retry-After", "1")
                        .json("{\"error\":\"Too Many Requests\",\"status\":429}");
                return;
            }
            next.handle(req, resp);
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