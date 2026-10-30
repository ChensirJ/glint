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
 * <p>The listen port comes from {@code server.port} and the bind address from
 * {@code server.address} (all interfaces when unset). Glint features (diagnostics,
 * static mount, access log, CORS, rate limit) come from {@link GlintProperties}.
 */
public class GlintServletWebServerFactory extends AbstractServletWebServerFactory {

    private GlintProperties properties = new GlintProperties();

    public GlintServletWebServerFactory() {
    }

    public GlintServletWebServerFactory(GlintProperties properties) {
        this.properties = properties == null ? new GlintProperties() : properties;
    }

    public GlintProperties getProperties() {
        return properties;
    }

    public void setDiagnostics(boolean diagnostics) {
        properties.setDiagnostics(diagnostics);
    }

    public boolean isDiagnostics() {
        return properties.isDiagnostics();
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
            context.initialize();
            return new GlintWebServer(getPort(), getAddress(), context, properties);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start Glint", e);
        }
    }
}