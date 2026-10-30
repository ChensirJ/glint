package io.github.glint.servlet;

import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.router.RouteHandler;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.List;

/**
 * Bridges Glint HTTP requests to a registered Jakarta {@link Servlet}
 * (typically Spring's {@code DispatcherServlet}), running every matching
 * registered {@link Filter} first ({@link DispatcherType#REQUEST}).
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
        String path = request.path();
        String servletName = context.findServletNameForPath(path);
        Servlet servlet = servletName == null ? null : context.servlets().get(servletName);
        List<Filter> filters = context.filtersFor(path, servletName, DispatcherType.REQUEST);
        if (servlet == null && filters.isEmpty()) {
            response.status(404).text("No servlet registered for " + path);
            return;
        }
        GlintHttpServletResponse resp = new GlintHttpServletResponse(response);
        GlintHttpServletRequest req = new GlintHttpServletRequest(request, context, resp);
        try {
            new GlintFilterChain(filters, servlet).doFilter(req, resp);
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