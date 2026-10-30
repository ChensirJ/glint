package io.github.glint.obs;

import java.util.Map;

/**
 * Optional observability export SPI.
 *
 * <p>{@code glint-core} has <strong>zero required</strong> third-party deps.
 * Implementations may push snapshots to OpenTelemetry, Prometheus, or custom
 * backends and are discovered via {@link java.util.ServiceLoader} when present
 * on the classpath.
 *
 * <p>Register with {@code META-INF/services/io.github.glint.obs.ObsExporter}.
 */
public interface ObsExporter {

    /** Stable exporter name for logs and the diagnostics UI. */
    String name();

    /**
     * Called after each request metrics update with the current
     * {@link Metrics#snapshot()}. Implementations must be non-blocking
     * or offload work to another thread (never pin a virtual thread).
     */
    default void onMetrics(Map<String, Object> snapshot) {
        // optional
    }

    /**
     * Called when a request finishes with its trace context.
     * Default is a no-op so simple metric-only exporters stay tiny.
     */
    default void onRequest(Trace.Ctx ctx, long durationNs) {
        // optional
    }
}
