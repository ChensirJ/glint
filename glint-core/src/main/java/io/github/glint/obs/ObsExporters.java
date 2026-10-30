package io.github.glint.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads and fans out to {@link ObsExporter} implementations.
 * Safe to call from virtual threads; exporter failures are isolated.
 */
public final class ObsExporters {

    private static final CopyOnWriteArrayList<ObsExporter> EXPORTERS = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded;

    private ObsExporters() {
    }

    /** Explicit registration (tests / embedding). */
    public static void register(ObsExporter exporter) {
        if (exporter != null) {
            EXPORTERS.addIfAbsent(exporter);
        }
    }

    public static void clear() {
        EXPORTERS.clear();
        loaded = false;
    }

    public static List<ObsExporter> all() {
        ensureLoaded();
        return List.copyOf(EXPORTERS);
    }

    public static void emitMetrics(Map<String, Object> snapshot) {
        ensureLoaded();
        for (ObsExporter e : EXPORTERS) {
            try {
                e.onMetrics(snapshot);
            } catch (Exception ex) {
                System.err.println("[glint-obs] exporter " + e.name() + " metrics failed: " + ex);
            }
        }
    }

    public static void emitRequest(Trace.Ctx ctx, long durationNs) {
        ensureLoaded();
        for (ObsExporter e : EXPORTERS) {
            try {
                e.onRequest(ctx, durationNs);
            } catch (Exception ex) {
                System.err.println("[glint-obs] exporter " + e.name() + " request failed: " + ex);
            }
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (ObsExporters.class) {
            if (loaded) {
                return;
            }
            for (ObsExporter e : ServiceLoader.load(ObsExporter.class)) {
                EXPORTERS.addIfAbsent(e);
            }
            loaded = true;
        }
    }
}
