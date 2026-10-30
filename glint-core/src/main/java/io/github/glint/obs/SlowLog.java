package io.github.glint.obs;

/**
 * Dumps slow-request details to stderr. Kept separate so Trace stays focused.
 */
public final class SlowLog {

    private SlowLog() {
    }

    public static void dump(Trace.Ctx ctx, long durNs) {
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
}
