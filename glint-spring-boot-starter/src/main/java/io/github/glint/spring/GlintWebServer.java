package io.github.glint.spring;

import io.github.glint.diag.DiagnosticsHandler;
import io.github.glint.http.HttpMethod;
import io.github.glint.http.VtServer;
import io.github.glint.obs.PinningWatcher;
import io.github.glint.router.Router;
import io.github.glint.servlet.GlintServletDispatcher;
import io.github.glint.servlet.GlintServletContext;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

import java.io.IOException;

/**
 * Spring {@link WebServer} backed by Glint's virtual-thread {@link VtServer}.
 */
public final class GlintWebServer implements WebServer {

    private final VtServer server;
    private final int port;

    public GlintWebServer(int port, GlintServletContext context, boolean diagnostics) throws IOException {
        this.port = port;
        Router router = new Router();
        if (diagnostics) {
            DiagnosticsHandler.install(router, "/_vt");
        }
        // Catch-all: every non-diagnostics path goes to the servlet dispatcher.
        GlintServletDispatcher dispatcher = new GlintServletDispatcher(context);
        router.add(HttpMethod.GET, "/", dispatcher);
        // Fallback: match any path by registering a greedy pattern for common methods.
        for (HttpMethod m : new HttpMethod[]{
                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            router.add(m, "/**", dispatcher);
        }
        // Our router does not support /** glob — register a custom matcher via wrap.
        // Instead, use a dedicated catch-all handler through VtServer directly.
        this.server = new VtServer(port, (req, resp) -> {
            if (req.path().startsWith("/_vt") && diagnostics) {
                var h = router.match(req);
                if (h != null) {
                    h.handle(req, resp);
                    return;
                }
            }
            dispatcher.handle(req, resp);
        });
    }

    @Override
    public void start() throws WebServerException {
        PinningWatcher.start();
        server.start();
    }

    @Override
    public void stop() throws WebServerException {
        try {
            server.close();
        } catch (IOException e) {
            throw new WebServerException("Failed to stop Glint", e);
        }
    }

    @Override
    public int getPort() {
        return server.getPort();
    }

    @Override
    public void shutDownGracefully(GracefulShutdownCallback callback) {
        stop();
        callback.shutdownComplete(GracefulShutdownResult.IDLE);
    }
}
