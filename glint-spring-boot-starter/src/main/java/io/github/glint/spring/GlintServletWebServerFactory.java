package io.github.glint.spring;

import io.github.glint.servlet.GlintServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;

/**
 * {@link ServletWebServerFactory} that boots Spring MVC on Glint instead of Tomcat.
 *
 * <p>Port and address come from {@link AbstractServletWebServerFactory}
 * ({@code server.port}, {@code server.address}). Diagnostics are controlled by
 * {@link GlintProperties#isDiagnostics()} / {@link #setDiagnostics(boolean)}.
 */
public class GlintServletWebServerFactory extends AbstractServletWebServerFactory {

    private boolean diagnostics = true;

    public void setDiagnostics(boolean diagnostics) {
        this.diagnostics = diagnostics;
    }

    public boolean isDiagnostics() {
        return diagnostics;
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        GlintServletContext context = new GlintServletContext();
        for (ServletContextInitializer initializer : initializers) {
            try {
                initializer.onStartup(context);
            } catch (ServletException e) {
                throw new IllegalStateException("ServletContextInitializer failed", e);
            }
        }
        try {
            context.initServlets();
            return new GlintWebServer(getPort(), context, diagnostics);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start Glint", e);
        }
    }
}