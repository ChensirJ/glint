package io.github.glint.bench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Report labels and partial-run merging for the three realistic servers. */
class RealisticBenchReportTest {

    @Test
    void defaultSelectionIsGlintTomcatAndTomcatVt() {
        assertEquals(List.of("glint", "tomcat", "tomcat-vt"), RealisticBench.selectedServers());
        assertEquals("Glint", RealisticBench.serverLabel("glint"));
        assertEquals("Tomcat", RealisticBench.serverLabel("tomcat"));
        assertEquals("Tomcat-VT", RealisticBench.serverLabel("tomcat-vt"));
    }

    @Test
    void serverListAcceptsCommaOrWhitespace() {
        String previous = System.setProperty("glint.bench.servers", "tomcat glint");
        try {
            assertEquals(List.of("tomcat", "glint"), RealisticBench.selectedServers());
            assertFalse(RealisticBench.allServersSelected());
        } finally {
            restoreServers(previous);
        }
    }

    @Test
    void rejectsUnknownServerKind() {
        String previous = System.setProperty("glint.bench.servers", "tomcat,jetty");
        try {
            assertThrows(IllegalArgumentException.class, RealisticBench::selectedServers);
        } finally {
            restoreServers(previous);
        }
    }

    @Test
    void recognizesTomcatVtResultRows() {
        assertTrue(RealisticBench.isMainResultRow(
                "| blocking-50ms | 100 | Tomcat-VT | 1 | 1-1 | 1.00 | 1.00 | 1.00 | 0 | 0 | 0 | 1 | 1 | 1 |"));
        assertTrue(RealisticBench.isMainResultRow(
                "| blocking-50ms | 100 | Tomcat | 1 | 1-1 | 1.00 | 1.00 | 1.00 | 0 | 0 | 0 | 1 | 1 | 1 |"));
        assertFalse(RealisticBench.isMainResultRow("| Tomcat-VT | api | 10 | 1.00 | 1.00 | 1.00 |"));
    }

    @Test
    void mergeInsertsTomcatVtWithoutDroppingPlatformRows() {
        String merged = RealisticBench.mergeRealisticSection(previousTable(), tomcatVtOnly(),
                Set.of(new RealisticBench.Scenario("blocking", "blocking-50ms", List.of(), 100, true)));
        assertTrue(merged.contains("| blocking-50ms | 100 | Glint | 1588 |"));
        assertTrue(merged.contains("| blocking-50ms | 100 | Tomcat | 1588 |"));
        assertTrue(merged.contains("| blocking-50ms | 100 | Tomcat-VT | 4000 |"));
        assertTrue(merged.contains("| keep-alive | 64 | Glint | 9 |"));
        assertTrue(merged.contains("| keep-alive | 64 | Tomcat | 8 |"));
        assertTrue(merged.contains("## Windows timer note"));
        assertTrue(merged.contains("- Captured: **new**"));
        int glint = merged.indexOf("| blocking-50ms | 100 | Glint |");
        int tomcat = merged.indexOf("| blocking-50ms | 100 | Tomcat |");
        int vt = merged.indexOf("| blocking-50ms | 100 | Tomcat-VT |");
        assertTrue(glint < tomcat && tomcat < vt, merged);
        assertTrue(vt < merged.indexOf("| keep-alive | 64 | Glint |"), merged);
    }

    @Test
    void mergeReplacesOnlyTheRemeasuredServer() {
        String fresh = """
                ## Realistic suite

                - Captured: **new**

                | Scenario | Clients | Server | req/s (median) |
                |----------|--------:|--------|---------------:|
                | blocking-50ms | 100 | Glint | 1700 |
                | blocking-50ms | 100 | Tomcat | 1600 |
                | blocking-50ms | 100 | Tomcat-VT | 4000 |
                | keep-alive | 64 | Glint | 1 |
                """;
        String merged = RealisticBench.mergeRealisticSection(previousTable(), fresh,
                Set.of(new RealisticBench.Scenario("blocking", "blocking-50ms", List.of(), 100, true)));
        assertTrue(merged.contains("| blocking-50ms | 100 | Glint | 1700 |"));
        assertTrue(merged.contains("| blocking-50ms | 100 | Tomcat | 1600 |"));
        assertTrue(merged.contains("| blocking-50ms | 100 | Tomcat-VT | 4000 |"));
        assertFalse(merged.contains("| blocking-50ms | 100 | Glint | 1588 |"));
        assertTrue(merged.contains("| keep-alive | 64 | Tomcat | 8 |"));
        assertFalse(merged.contains("| keep-alive | 64 | Glint | 1 |"));
    }

    private static String previousTable() {
        return """
                ## Realistic suite (Glint vs Tomcat, same app logic)

                - Captured: **old**

                | Scenario | Clients | Server | req/s (median) | req/s min-max | p50 ms | p99 ms | p99.9 ms | errors | connect errors | timeouts | heap peak MiB | RSS MiB | platform threads peak |
                |----------|--------:|--------|---------------:|---------------|-------:|-------:|---------:|-------:|---------------:|---------:|--------------:|--------:|----------------------:|
                | blocking-50ms | 100 | Glint | 1588 | 1581-1588 | 62.42 | 64.69 | 65.82 | 0 | 0 | 0 | 116 | 183 | 33 |
                | blocking-50ms | 100 | Tomcat | 1588 | 1588-1596 | 62.69 | 67.85 | 72.68 | 0 | 0 | 0 | 96 | 188 | 118 |
                | keep-alive | 64 | Glint | 9 | 9-9 | 1.00 | 1.00 | 1.00 | 0 | 0 | 0 | 1 | 1 | 1 |
                | keep-alive | 64 | Tomcat | 8 | 8-8 | 1.00 | 1.00 | 1.00 | 0 | 0 | 0 | 1 | 1 | 1 |

                ## Windows timer note (json+db+cpu before/after)

                keep this note
                """;
    }

    private static String tomcatVtOnly() {
        return """
                ## Realistic suite (Glint vs Tomcat vs Tomcat-VT, same app logic)

                - Captured: **new**

                | Scenario | Clients | Server | req/s (median) | req/s min-max | p50 ms | p99 ms | p99.9 ms | errors | connect errors | timeouts | heap peak MiB | RSS MiB | platform threads peak |
                |----------|--------:|--------|---------------:|---------------|-------:|-------:|---------:|-------:|---------------:|---------:|--------------:|--------:|----------------------:|
                | blocking-50ms | 100 | Tomcat-VT | 4000 | 4000-4000 | 62.00 | 64.00 | 65.00 | 0 | 0 | 0 | 10 | 20 | 30 |
                """;
    }

    private static void restoreServers(String previous) {
        if (previous == null) {
            System.clearProperty("glint.bench.servers");
        } else {
            System.setProperty("glint.bench.servers", previous);
        }
    }
}
