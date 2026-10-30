package io.github.glint.obs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retains recent slow requests for the diagnostics dashboard and dumps details to stderr.
 */
public final class SlowLog {

    public record SlowRequest(
            String id,
            String path,
            long durationMs,
            long epochMs,
            List<Map<String, Object>> spans) {
    }

    static final EventRing<SlowRequest> RECENT = new EventRing<>(32);

    private SlowLog() {
    }

    public static void dump(Trace.Ctx ctx, long durNs) {
        long durMs = Math.round(durNs / 1_000_000.0);
        List<Map<String, Object>> spans = new ArrayList<>();
        for (Trace.Span span : ctx.spans()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", span.name());
            m.put("ms", Math.round(span.durNs() / 1_000_000.0 * 10) / 10.0);
            spans.add(m);
        }
        RECENT.add(new SlowRequest(ctx.id(), ctx.path(), durMs, System.currentTimeMillis(), spans));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "[SLOW %.1fms] id=%s path=%s%n",
                durNs / 1_000_000.0,
                ctx.id(),
                ctx.path()));
        for (Trace.Span span : ctx.spans()) {
            sb.append(String.format(
                    "  span %-24s %.1fms%n",
                    span.name(),
                    span.durNs() / 1_000_000.0));
        }
        System.err.print(sb);
    }

    public static List<SlowRequest> recent() {
        return RECENT.snapshot();
    }
}
