package io.github.glint;

import io.github.glint.diag.DiagnosticsHandler;
import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.http.ServerConfig;
import io.github.glint.http.VtServer;
import io.github.glint.middleware.Middleware;
import io.github.glint.obs.PinningWatcher;
import io.github.glint.router.RouteHandler;
import io.github.glint.router.Router;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fluent entry point for a standalone Glint server.
 *
 * <pre>{@code
 * Glint.create(8080)
 *     .get("/hello", (req, resp) -> resp.text("Hello"))
 *     .diagnostics("/_vt")
 *     .start();
 * }</pre>
 *
 * Designed for JDK 21+: every accepted connection is served on a virtual thread.
 */
public final class Glint {

    private final int port;
    private final Router router = new Router();
    private final List<Middleware> before = new ArrayList<>();
    private String diagnosticsPath;
    private boolean pinningWatcher = true;
    private ServerConfig config = ServerConfig.defaults();
    private VtServer server;

    private Glint(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
    }

    /** Create a builder bound to the given TCP port (0 = ephemeral). */
    public static Glint create(int port) {
        return new Glint(port);
    }

    public Glint config(ServerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    public Glint get(String path, RouteHandler handler) {
        router.add(HttpMethod.GET, path, handler);
        return this;
    }

    public Glint post(String path, RouteHandler handler) {
        router.add(HttpMethod.POST, path, handler);
        return this;
    }

    public Glint put(String path, RouteHandler handler) {
        router.add(HttpMethod.PUT, path, handler);
        return this;
    }

    public Glint delete(String path, RouteHandler handler) {
        router.add(HttpMethod.DELETE, path, handler);
        return this;
    }

    public Glint patch(String path, RouteHandler handler) {
        router.add(HttpMethod.PATCH, path, handler);
        return this;
    }

    public Glint head(String path, RouteHandler handler) {
        router.add(HttpMethod.HEAD, path, handler);
        return this;
    }

    public Glint options(String path, RouteHandler handler) {
        router.add(HttpMethod.OPTIONS, path, handler);
        return this;
    }

    /** Register a middleware that runs before the matched route handler. */
    public Glint before(Middleware middleware) {
        before.add(Objects.requireNonNull(middleware, "middleware"));
        return this;
    }

    /**
     * Enable the built-in diagnostics JSON endpoints under {@code path}
     * (for example {@code /_vt}). The dashboard UI (if on the classpath)
     * is also served from the same prefix.
     */
    public Glint diagnostics(String path) {
        this.diagnosticsPath = Objects.requireNonNull(path, "path");
        return this;
    }

    /** Enable or disable the JFR virtual-thread pinning watcher (default true). */
    public Glint pinningWatcher(boolean enabled) {
        this.pinningWatcher = enabled;
        return this;
    }

    /**
     * Start the acceptor and begin serving. Blocks the calling thread until
     * {@link #stop()} is invoked from another thread, unless you call
     * {@link #startAsync()} instead.
     */
    public Glint start() throws IOException {
        startAsync();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
        return this;
    }

    /** Start without blocking the calling thread. */
    public Glint startAsync() throws IOException {
        if (diagnosticsPath != null) {
            DiagnosticsHandler.install(router, diagnosticsPath);
        }
        RouteHandler pipeline = buildPipeline();
        server = new VtServer(port, pipeline, config);
        if (pinningWatcher) {
            PinningWatcher.start();
        }
        server.start();
        return this;
    }

    /** Gracefully stop accepting and wait for in-flight requests. */
    public void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Best-effort shutdown.
            }
            server = null;
        }
    }

    /** Bound listen port after start (useful when port was 0). */
    public int port() {
        return server != null ? server.getPort() : port;
    }

    public Router router() {
        return router;
    }

    private RouteHandler buildPipeline() {
        RouteHandler matched = (req, resp) -> {
            Router.Match m = router.matchDetailed(req);
            if (m.methodNotAllowed()) {
                String allow = m.allowed().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
                resp.header("Allow", allow);
                resp.status(405).json("{\"error\":\"Method Not Allowed\",\"status\":405,\"allow\":\"" + allow + "\"}");
                return;
            }
            if (m.handler() == null) {
                resp.status(404).json("{\"error\":\"Not Found\",\"status\":404}");
                return;
            }
            m.handler().handle(req, resp);
        };
        RouteHandler chain = matched;
        for (int i = before.size() - 1; i >= 0; i--) {
            Middleware mw = before.get(i);
            RouteHandler next = chain;
            chain = (req, resp) -> mw.handle(req, resp, next);
        }
        return chain;
    }
}
