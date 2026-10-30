package io.github.glint.servlet;

import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.router.RouteHandler;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;

import java.io.IOException;

/**
 * Bridges Glint HTTP requests to a registered Jakarta {@link Servlet}
 * (typically Spring's {@code DispatcherServlet}).
 *
 * <p>When the servlet starts async processing, this dispatcher waits for
 * {@link GlintAsyncContext#complete()} before returning to VtServer.
 */
public final class GlintServletDispatcher implements RouteHandler {

    private final GlintServletContext context;

    public GlintServletDispatcher(GlintServletContext context) {
        this.context = context;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Exception {
        Servlet servlet = context.findServletForPath(request.path());
        if (servlet == null) {
            response.status(404).text("No servlet registered for " + request.path());
            return;
        }
        GlintHttpServletResponse resp = new GlintHttpServletResponse(response);
        GlintHttpServletRequest req = new GlintHttpServletRequest(request, context, resp);
        try {
            servlet.service(req, resp);
            if (req.isAsyncStarted()) {
                ((GlintAsyncContext) req.getAsyncContext()).awaitCompletion();
            } else {
                resp.commitToGlint();
            }
        } catch (ServletException | IOException e) {
            if (!response.committed()) {
                response.status(500).text("Servlet error: " + e.getMessage());
            }
            throw e;
        }
    }
}
