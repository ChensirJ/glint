package io.github.glint.obs;

import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for {@code jdk.VirtualThreadPinned} JFR events and correlates them
 * with the in-flight Glint request via {@link Trace#BY_THREAD}.
 *
 * <p>On JDK 21, blocking inside {@code synchronized} pins the virtual thread
 * to its carrier — this watcher surfaces that as a first-class diagnostic.
 */
public final class PinningWatcher {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private PinningWatcher() {
    }

    /** Start the async JFR stream once per JVM. Safe to call repeatedly. */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform().name("glint-pinning-watcher").daemon(true).start(() -> {
            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("jdk.VirtualThreadPinned")
                        .withThreshold(Duration.ofMillis(1))
                        .withStackTrace();
                rs.onEvent("jdk.VirtualThreadPinned", event -> {
                    Trace.METRICS.recordPinning();
                    long tid = event.getThread() != null
                            ? event.getThread().getJavaThreadId()
                            : -1L;
                    Trace.Ctx ctx = tid >= 0 ? Trace.forThread(tid) : null;
                    System.err.printf(
                            "[PINNED %dms] req=%s path=%s%n%s%n",
                            event.getDuration().toMillis(),
                            ctx == null ? "-" : ctx.id(),
                            ctx == null ? "-" : ctx.path(),
                            event.getStackTrace());
                });
                rs.start(); // blocks daemon thread until JVM exit / stream close
            } catch (Exception e) {
                System.err.println("[Glint] PinningWatcher failed to start: " + e.getMessage());
                STARTED.set(false);
            }
        });
    }
}
