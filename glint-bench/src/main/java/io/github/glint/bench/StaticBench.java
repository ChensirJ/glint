package io.github.glint.bench;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import io.github.glint.http.staticfiles.StaticOptions;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.servlets.DefaultServlet;

import java.io.File;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Glint vs Tomcat DefaultServlet static-file microbench (1 KiB / 100 KiB / ~2 MiB).
 *
 * <pre>
 *   mvn -pl glint-bench -am exec:java -Dexec.mainClass=io.github.glint.bench.StaticBench
 * </pre>
 *
 * Appends a section to {@code docs/bench-results.md} (keeps the existing /hello table).
 */
public final class StaticBench {

    private static final int[] SIZES = {1024, 100 * 1024, 2 * 1024 * 1024};

    public static void main(String[] args) throws Exception {
        int warmupSec = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 64;

        Path root = Files.createTempDirectory("glint-static-bench");
        for (int size : SIZES) {
            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) {
                data[i] = (byte) (i & 0xff);
            }
            Files.write(root.resolve("f-" + size + ".bin"), data);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        Glint glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(10_000).shutdownWaitMs(1_000).build())
                .staticFiles("/s", root, StaticOptions.defaults())
                .startAsync();
        int glintPort = glint.port();

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(Files.createTempDirectory("glint-tomcat-static").toString());
        Context ctx = tomcat.addContext("", root.toAbsolutePath().toString());
        Tomcat.initWebappDefaults(ctx);
        // Ensure DefaultServlet is mapped; initWebappDefaults already does this.
        if (ctx.findChild("default") == null) {
            Tomcat.addServlet(ctx, "default", new DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");
        }
        tomcat.getConnector();
        tomcat.start();
        int tomcatPort = tomcat.getConnector().getLocalPort();

        String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now());

        StringBuilder section = new StringBuilder();
        section.append("\n## Static files (Glint vs Tomcat DefaultServlet)\n\n");
        section.append("- Captured: **").append(when).append("** (Asia/Shanghai)\n");
        section.append("- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, ");
        section.append("warmup ").append(warmupSec).append("s + measure ").append(durationSec);
        section.append("s, concurrency ").append(concurrency).append('\n');
        section.append("- Glint: `staticFiles` with default StaticOptions ");
        section.append("(small-file cache + zero-copy for large files)\n");
        section.append("- Tomcat: embed ").append(org.apache.catalina.util.ServerInfo.getServerNumber());
        section.append(" `DefaultServlet` / `initWebappDefaults`, docBase = temp dir\n");
        section.append("- Re-run: `powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1`\n\n");
        section.append("| Size | Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |\n");
        section.append("|------|--------|------:|-------:|---------:|---------:|---------:|\n");

        System.out.println("=== Static Glint vs Tomcat (" + when + ") ===");
        System.out.printf("warmup=%ds duration=%ds concurrency=%d%n", warmupSec, durationSec, concurrency);

        for (int size : SIZES) {
            String name = "f-" + size + ".bin";
            String gUrl = "http://127.0.0.1:" + glintPort + "/s/" + name;
            String tUrl = "http://127.0.0.1:" + tomcatPort + "/" + name;

            runPhase(client, gUrl, concurrency, warmupSec, true);
            Result g = runPhase(client, gUrl, concurrency, durationSec, false);
            runPhase(client, tUrl, concurrency, warmupSec, true);
            Result t = runPhase(client, tUrl, concurrency, durationSec, false);

            String label = human(size);
            printResult(label + " Glint", g, durationSec);
            printResult(label + " Tomcat", t, durationSec);
            section.append(row(label, "Glint", g, durationSec));
            section.append(row(label, "Tomcat DefaultServlet", t, durationSec));
        }

        section.append("\nNumbers are machine-local and not a guarantee of production ranking.\n");

        glint.stop();
        tomcat.stop();
        tomcat.destroy();

        Path out = Path.of("docs", "bench-results.md");
        if (!Files.isDirectory(Path.of("docs"))) {
            Path sibling = Path.of("..", "docs");
            if (Files.isDirectory(sibling)) {
                out = sibling.resolve("bench-results.md");
            } else {
                out = Path.of("bench-results.md");
            }
        }
        String existing = Files.isRegularFile(out) ? Files.readString(out) : "";
        // Drop a previous static section so re-runs stay idempotent.
        int cut = existing.indexOf("\n## Static files");
        if (cut >= 0) {
            existing = existing.substring(0, cut);
        }
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            existing = existing + "\n";
        }
        Files.writeString(out, existing + section);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    private static String human(int size) {
        if (size >= 1024 * 1024) {
            return (size / (1024 * 1024)) + " MiB";
        }
        if (size >= 1024) {
            return (size / 1024) + " KiB";
        }
        return size + " B";
    }

    private static void printResult(String name, Result r, int durationSec) {
        List<Long> sorted = new ArrayList<>(r.latencies);
        Collections.sort(sorted);
        double thr = r.ok.sum() / (double) durationSec;
        System.out.printf("--- %s --- reqs=%d err=%d thr=%.0f/s", name, r.ok.sum(), r.err.sum(), thr);
        if (!sorted.isEmpty()) {
            System.out.printf(" p50=%.2f p99=%.2f max=%.2f ms",
                    sorted.get(pct(sorted.size(), 0.50)) / 1e6,
                    sorted.get(pct(sorted.size(), 0.99)) / 1e6,
                    sorted.get(sorted.size() - 1) / 1e6);
        }
        System.out.println();
    }

    private static String row(String size, String name, Result r, int duration) {
        List<Long> sorted = new ArrayList<>(r.latencies);
        Collections.sort(sorted);
        double thr = r.ok.sum() / (double) duration;
        if (sorted.isEmpty()) {
            return String.format("| %s | %s | %.0f | %d | - | - | - |%n", size, name, thr, r.err.sum());
        }
        return String.format("| %s | %s | %.0f | %d | %.2f | %.2f | %.2f |%n",
                size, name, thr, r.err.sum(),
                sorted.get(pct(sorted.size(), 0.50)) / 1e6,
                sorted.get(pct(sorted.size(), 0.99)) / 1e6,
                sorted.get(sorted.size() - 1) / 1e6);
    }

    private static int pct(int size, double p) {
        int i = (int) Math.ceil(size * p) - 1;
        return Math.max(0, Math.min(size - 1, i));
    }

    private static Result runPhase(HttpClient client, String url, int concurrency,
                                   int seconds, boolean warmup) throws Exception {
        LongAdder ok = new LongAdder();
        LongAdder err = new LongAdder();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<Future<?>> futures = new ArrayList<>(concurrency);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    try {
                        HttpResponse<byte[]> resp = client.send(request,
                                HttpResponse.BodyHandlers.ofByteArray());
                        long dt = System.nanoTime() - t0;
                        if (resp.statusCode() == 200) {
                            ok.increment();
                            if (!warmup && latencies.size() < 200_000) {
                                latencies.add(dt);
                            }
                        } else {
                            err.increment();
                        }
                    } catch (Exception e) {
                        err.increment();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.close();
        return new Result(ok, err, latencies);
    }

    private record Result(LongAdder ok, LongAdder err, List<Long> latencies) {
    }
}
