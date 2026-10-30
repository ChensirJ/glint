package io.github.glint.obs;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for {@code jdk.VirtualThreadPinned} JFR events and correlates them
 * with the in-flight Glint request via {@link Trace#forThread(long)}.
 *
 * <p>On JDK 21, blocking inside {@code synchronized} pins the virtual thread
 * to its carrier  -  this watcher surfaces that as a first-class diagnostic.
 */
public final class PinningWatcher {

    public record PinEvent(
            long durationMs,
            String reqId,
            String path,
            String stack,
            long epochMs) {
    }

    static final EventRing<PinEvent> RECENT = new EventRing<>(32);
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
                rs.onEvent("jdk.VirtualThreadPinned", PinningWatcher::onPinned);
                rs.start();
            } catch (Exception e) {
                System.err.println("[Glint] PinningWatcher failed to start: " + e.getMessage());
                STARTED.set(false);
            }
        });
    }

    private static void onPinned(RecordedEvent event) {
        Trace.METRICS.recordPinning();
        long tid = event.getThread() != null ? event.getThread().getJavaThreadId() : -1L;
        Trace.Ctx ctx = tid >= 0 ? Trace.forThread(tid) : null;
        String stack = truncateStack(event.getStackTrace(), 12);
        PinEvent pe = new PinEvent(
                event.getDuration().toMillis(),
                ctx == null ? "-" : ctx.id(),
                ctx == null ? "-" : ctx.path(),
                stack,
                System.currentTimeMillis());
        RECENT.add(pe);
        System.err.printf(
                "[PINNED %dms] req=%s path=%s%n%s%n",
                pe.durationMs(),
                pe.reqId(),
                pe.path(),
                pe.stack());
    }

    private static String truncateStack(RecordedStackTrace st, int maxFrames) {
        if (st == null) {
            return "";
        }
        List<RecordedFrame> frames = st.getFrames();
        StringBuilder sb = new StringBuilder();
        int n = Math.min(maxFrames, frames.size());
        for (int i = 0; i < n; i++) {
            RecordedFrame f = frames.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(f.getMethod().getType().getName())
                    .append('.').append(f.getMethod().getName())
                    .append(':').append(f.getLineNumber());
        }
        if (frames.size() > maxFrames) {
            sb.append("\n  ... ").append(frames.size() - maxFrames).append(" more");
        }
        return sb.toString();
    }

    public static List<PinEvent> recent() {
        return RECENT.snapshot();
    }

    public static List<Map<String, Object>> recentAsMaps() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (PinEvent e : recent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("durationMs", e.durationMs());
            m.put("reqId", e.reqId());
            m.put("path", e.path());
            m.put("stack", e.stack());
            m.put("epochMs", e.epochMs());
            out.add(m);
        }
        return out;
    }
}
