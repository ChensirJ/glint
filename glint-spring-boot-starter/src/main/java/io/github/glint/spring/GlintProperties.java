package io.github.glint.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Glint Spring Boot starter ({@code glint.*}).
 *
 * <p>Virtual threads are always used for accepted connections; that behavior is
 * not configurable. CORS, access logs, rate limits, and static mounts are
 * standalone {@code Glint} / middleware APIs and are not wired through these
 * properties in the current starter.
 *
 * @see GlintServerAutoConfiguration
 */
@ConfigurationProperties(prefix = "glint")
public class GlintProperties {

    /**
     * When {@code true} (default), mounts the {@code /_vt} diagnostics dashboard
     * and related JSON APIs on the Glint web server.
     */
    private boolean diagnostics = true;

    public boolean isDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(boolean diagnostics) {
        this.diagnostics = diagnostics;
    }
}