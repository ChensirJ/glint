package io.github.glint.spring;

import io.github.glint.diag.DiagnosticsHandler;
import io.github.glint.http.VtServer;
import io.github.glint.http.staticfiles.StaticFiles;
import io.github.glint.http.staticfiles.StaticOptions;
import io.github.glint.middleware.CorsOptions;
import io.github.glint.middleware.Middleware;
import io.github.glint.middleware.Middlewares;
import io.github.glint.obs.PinningWatcher;
import io.github.glint.router.RouteHandler;
import io.github.glint.router.Router;
import io.github.glint.servlet.GlintServletContext;
import io.github.glint.servlet.GlintServletDispatcher;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring {@link WebServer} backed by Glint's virtual-thread {@link VtServer}.
 *
 * <p>Request pipeline (outermost first): access log, CORS, rate limit
 * (diagnostics paths exempt), then {@code /_vt} diagnostics, the optional
 * static mount, and finally the servlet filter chain plus
 * {@code DispatcherServlet}.
 */
public final class GlintWebServer implements WebServer {

    private final VtServer server;
    private final GlintServletContext context;

    public GlintWebServer(int port, GlintServletContext context, boolean diagnostics) throws IOException {
        this(port, context, propertiesWithDiagnostics(diagnostics));
    }

    public GlintWebServer(int port, GlintServletContext context, GlintProperties properties) throws IOException {
        this.context = context;
        this.server = new VtServer(port, buildPipeline(context, properties));
    }

    private static GlintProperties propertiesWithDiagnostics(boolean diagnostics) {
        GlintProperties p = new GlintProperties();
        p.setDiagnostics(diagnostics);
        return p;
    }

    static RouteHandler buildPipeline(GlintServletContext context, GlintProperties props) {
        Router router = new Router();
        boolean diagnostics = props.isDiagnostics();
        if (diagnostics) {
            DiagnosticsHandler.install(router, "/_vt");
        }
        mountStatic(router, props.getStatic());
        GlintServletDispatcher dispatcher = new GlintServletDispatcher(context);
        RouteHandler handler = (req, resp) -> {
            RouteHandler h = router.match(req);
            if (h != null) {
                h.handle(req, resp);
                return;
            }
            dispatcher.handle(req, resp);
        };

        List<Middleware> chain = new ArrayList<>();
        GlintProperties.AccessLog log = props.getAccessLog();
        if (log.isEnabled()) {
            chain.add(log.getFormat() == GlintProperties.AccessLogFormat.JSON
                    ? Middlewares.accessLogJson()
                    : Middlewares.accessLog());
        }
        GlintProperties.Cors cors = props.getCors();
        if (cors.isEnabled()) {
            chain.add(Middlewares.cors(CorsOptions.builder()
                    .allowOrigin(cors.getAllowedOrigin())
                    .allowMethods(cors.getAllowedMethods().toArray(String[]::new))
                    .allowHeaders(cors.getAllowedHeaders().toArray(String[]::new))
                    .exposeHeaders(cors.getExposedHeaders().toArray(String[]::new))
                    .allowCredentials(cors.isAllowCredentials())
                    .maxAgeSeconds(cors.getMaxAgeSeconds())
                    .shortCircuitOptions(cors.isShortCircuitOptions())
                    .build()));
        }
        GlintProperties.RateLimit rl = props.getRateLimit();
        if (rl.isEnabled()) {
            Middleware limiter = Middlewares.rateLimit(rl.getPermitsPerSecond(), rl.getBurst());
            chain.add((req, resp, next) -> {
                if (diagnostics && req.path().startsWith("/_vt")) {
                    next.handle(req, resp);
                } else {
                    limiter.handle(req, resp, next);
                }
            });
        }
        RouteHandler out = handler;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware mw = chain.get(i);
            RouteHandler next = out;
            out = (req, resp) -> mw.handle(req, resp, next);
        }
        return out;
    }

    private static void mountStatic(Router router, GlintProperties.StaticResources s) {
        if (!s.isEnabled()) {
            return;
        }
        String location = s.getLocation();
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("glint.static.location is required when glint.static.enabled=true");
        }
        if (location.startsWith("classpath:")) {
            StaticFiles.mountClasspath(router, s.getPrefix(), location.substring("classpath:".length()));
            return;
        }
        StaticOptions.Builder options = StaticOptions.builder()
                .cacheMaxEntryBytes(s.getCacheMaxEntryBytes())
                .cacheMaxTotalBytes(s.getCacheMaxTotalBytes())
                .revalidateMillis(s.getRevalidateMillis())
                .precompressed(s.isPrecompressed())
                .directoryListing(s.isDirectoryListing());
        String cc = s.getCacheControl();
        if (cc != null && !cc.isBlank()) {
            options.defaultCacheControl(cc);
        }
        StaticFiles.mountDirectory(router, s.getPrefix(), Path.of(location), options.build());
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
        } finally {
            context.destroy();
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