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
        GlintHttpServletRequest req = new GlintHttpServletRequest(request, context);
        GlintHttpServletResponse resp = new GlintHttpServletResponse(response);
        try {
            servlet.service(req, resp);
            resp.commitToGlint();
        } catch (ServletException | IOException e) {
            response.status(500).text("Servlet error: " + e.getMessage());
            throw e;
        }
    }
}
