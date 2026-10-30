package io.github.glint.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the Glint Spring Boot starter ({@code glint.*}).
 *
 * <p>Virtual threads are always used for accepted connections; that is not a
 * toggle. Every optional feature below is off by default except diagnostics.
 *
 * @see GlintServerAutoConfiguration
 */
@ConfigurationProperties(prefix = "glint")
public class GlintProperties {

    /** Mount the {@code /_vt} diagnostics dashboard and JSON APIs. */
    private boolean diagnostics = true;

    private final StaticResources staticResources = new StaticResources();
    private final AccessLog accessLog = new AccessLog();
    private final Cors cors = new Cors();
    private final RateLimit rateLimit = new RateLimit();

    public boolean isDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(boolean diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Bound to {@code glint.static.*}. */
    public StaticResources getStatic() {
        return staticResources;
    }

    public AccessLog getAccessLog() {
        return accessLog;
    }

    public Cors getCors() {
        return cors;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /** {@code glint.static.*}: serve files directly from Glint, before Spring MVC. */
    public static class StaticResources {
        /** Enable the static mount. */
        private boolean enabled;
        /** URL prefix, for example {@code /assets}. */
        private String prefix = "/static";
        /**
         * Directory path, or {@code classpath:some/root} for classpath resources.
         * Required when enabled.
         */
        private String location;
        /** Files up to this size are cached in memory (0 disables). Directory mounts only. */
        private int cacheMaxEntryBytes = 256 * 1024;
        /** Upper bound for cached bytes of the mount. Directory mounts only. */
        private long cacheMaxTotalBytes = 64L * 1024 * 1024;
        /** How long file metadata is trusted before re-checking the filesystem. */
        private long revalidateMillis = 1000L;
        /** Cache-Control for non-fingerprinted files; unset sends none. Directory mounts only. */
        private String cacheControl;
        /** Serve precompressed .br / .gz siblings when accepted. */
        private boolean precompressed = true;
        /** HTML/JSON listing for directories without index.html. */
        private boolean directoryListing;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public int getCacheMaxEntryBytes() { return cacheMaxEntryBytes; }
        public void setCacheMaxEntryBytes(int v) { this.cacheMaxEntryBytes = v; }
        public long getCacheMaxTotalBytes() { return cacheMaxTotalBytes; }
        public void setCacheMaxTotalBytes(long v) { this.cacheMaxTotalBytes = v; }
        public long getRevalidateMillis() { return revalidateMillis; }
        public void setRevalidateMillis(long v) { this.revalidateMillis = v; }
        public String getCacheControl() { return cacheControl; }
        public void setCacheControl(String cacheControl) { this.cacheControl = cacheControl; }
        public boolean isPrecompressed() { return precompressed; }
        public void setPrecompressed(boolean precompressed) { this.precompressed = precompressed; }
        public boolean isDirectoryListing() { return directoryListing; }
        public void setDirectoryListing(boolean directoryListing) { this.directoryListing = directoryListing; }
    }

    /** Access log output format. */
    public enum AccessLogFormat {
        /** {@code METHOD path -> status (ms)}. */
        TEXT,
        /** JSON Lines: ts, method, path, status, durationMs, bytes, remote. */
        JSON
    }

    /** {@code glint.access-log.*}: one line per request to stdout. */
    public static class AccessLog {
        private boolean enabled;
        private AccessLogFormat format = AccessLogFormat.TEXT;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public AccessLogFormat getFormat() { return format; }
        public void setFormat(AccessLogFormat format) { this.format = format; }
    }

    /** {@code glint.cors.*}: Glint-level CORS headers applied to every response. */
    public static class Cors {
        private boolean enabled;
        private String allowedOrigin = "*";
        private List<String> allowedMethods = new ArrayList<>(List.of(
                "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));
        private List<String> allowedHeaders = new ArrayList<>(List.of(
                "Content-Type", "Authorization", "X-Requested-With", "Range"));
        private List<String> exposedHeaders = new ArrayList<>();
        private boolean allowCredentials;
        private int maxAgeSeconds = 86400;
        /** Answer preflight OPTIONS with 204 without reaching Spring. */
        private boolean shortCircuitOptions = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAllowedOrigin() { return allowedOrigin; }
        public void setAllowedOrigin(String allowedOrigin) { this.allowedOrigin = allowedOrigin; }
        public List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(List<String> v) { this.allowedMethods = v; }
        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> v) { this.allowedHeaders = v; }
        public List<String> getExposedHeaders() { return exposedHeaders; }
        public void setExposedHeaders(List<String> v) { this.exposedHeaders = v; }
        public boolean isAllowCredentials() { return allowCredentials; }
        public void setAllowCredentials(boolean v) { this.allowCredentials = v; }
        public int getMaxAgeSeconds() { return maxAgeSeconds; }
        public void setMaxAgeSeconds(int v) { this.maxAgeSeconds = v; }
        public boolean isShortCircuitOptions() { return shortCircuitOptions; }
        public void setShortCircuitOptions(boolean v) { this.shortCircuitOptions = v; }
    }

    /** {@code glint.rate-limit.*}: per-client-IP token bucket (429 + Retry-After). */
    public static class RateLimit {
        private boolean enabled;
        private double permitsPerSecond = 100.0;
        private int burst = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getPermitsPerSecond() { return permitsPerSecond; }
        public void setPermitsPerSecond(double v) { this.permitsPerSecond = v; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
    }
}