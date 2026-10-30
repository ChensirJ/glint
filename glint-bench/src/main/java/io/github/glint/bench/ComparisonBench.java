package io.github.glint.bench;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
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
 * Side-by-side Glint vs embedded Tomcat microbench on the same machine.
 *
 * <pre>
 *   mvn -pl glint-bench -am exec:java -Dexec.mainClass=io.github.glint.bench.ComparisonBench
 *   # args: warmupSec durationSec concurrency
 * </pre>
 *
 * Writes {@code docs/bench-results.md} when run from the repo root (or {@code ./bench-results.md}).
 */
public final class ComparisonBench {

    public static void main(String[] args) throws Exception {
        int warmupSec = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 64;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // --- Glint ---
        Glint glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(5_000).shutdownWaitMs(1_000).build())
                .get("/hello", (req, resp) -> resp.text("ok"))
                .startAsync();
        String glintUrl = "http://127.0.0.1:" + glint.port() + "/hello";
        runPhase(client, glintUrl, concurrency, warmupSec, true);
        Result glintResult = runPhase(client, glintUrl, concurrency, durationSec, false);
        glint.stop();

        // --- Tomcat embed ---
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(Files.createTempDirectory("glint-tomcat-bench").toString());
        Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(ctx, "hello", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain; charset=utf-8");
                resp.getWriter().write("ok");
            }
        });
        ctx.addServletMappingDecoded("/hello", "hello");
        tomcat.getConnector(); // init default connector
        tomcat.start();
        int tomcatPort = tomcat.getConnector().getLocalPort();
        String tomcatUrl = "http://127.0.0.1:" + tomcatPort + "/hello";
        runPhase(client, tomcatUrl, concurrency, warmupSec, true);
        Result tomcatResult = runPhase(client, tomcatUrl, concurrency, durationSec, false);
        tomcat.stop();
        tomcat.destroy();

        String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now());

        System.out.println("=== Glint vs Tomcat (" + when + ") ===");
        System.out.printf("warmup=%ds duration=%ds concurrency=%d payload=text/plain \"ok\" HTTP/1.1%n",
                warmupSec, durationSec, concurrency);
        printResult("Glint", glintResult, durationSec);
        printResult("Tomcat embed " + org.apache.catalina.util.ServerInfo.getServerNumber(),
                tomcatResult, durationSec);

        String md = toMarkdown(when, warmupSec, durationSec, concurrency, glintResult, tomcatResult);
        Path out = Path.of("docs", "bench-results.md");
        if (!Files.isDirectory(Path.of("docs"))) {
            Path sibling = Path.of("..", "docs");
            if (Files.isDirectory(sibling)) {
                out = sibling.resolve("bench-results.md");
            } else {
                out = Path.of("bench-results.md");
            }
        }
        Files.createDirectories(out.getParent() == null ? Path.of(".") : out.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.print(md);
        }
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    private static void printResult(String name, Result r, int durationSec) {
        List<Long> sorted = new ArrayList<>(r.latencies);
        Collections.sort(sorted);
        double thr = r.ok.sum() / (double) durationSec;
        System.out.printf("--- %s ---%n", name);
        System.out.printf("requests=%d  errors=%d  throughput=%.0f req/s%n",
                r.ok.sum(), r.err.sum(), thr);
        if (!sorted.isEmpty()) {
            System.out.printf("latency p50=%.2f ms  p99=%.2f ms  max=%.2f ms%n",
                    sorted.get(percentileIndex(sorted.size(), 0.50)) / 1_000_000.0,
                    sorted.get(percentileIndex(sorted.size(), 0.99)) / 1_000_000.0,
                    sorted.get(sorted.size() - 1) / 1_000_000.0);
        }
    }

    private static String toMarkdown(String when, int warmup, int duration, int concurrency,
                                     Result glint, Result tomcat) {
        List<Long> g = new ArrayList<>(glint.latencies);
        List<Long> t = new ArrayList<>(tomcat.latencies);
        Collections.sort(g);
        Collections.sort(t);
        StringBuilder sb = new StringBuilder();
        sb.append("# Glint vs Tomcat microbench\n\n");
        sb.append("- Captured: **").append(when).append("** (Asia/Shanghai)\n");
        sb.append("- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, same machine, ");
        sb.append("warmup ").append(warmup).append("s + measure ").append(duration).append("s, ");
        sb.append("concurrency ").append(concurrency).append(", path `/hello` body `ok`\n");
        sb.append("- Glint: in-process `Glint.create(0)` / Tomcat: `tomcat-embed-core` ephemeral port\n");
        sb.append("- Re-run: `powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1`\n\n");
        sb.append("| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |\n");
        sb.append("|--------|------:|-------:|---------:|---------:|---------:|\n");
        sb.append(row("Glint", glint, duration, g));
        sb.append(row("Tomcat embed", tomcat, duration, t));
        sb.append("\nNumbers are machine-local and not a guarantee of production ranking.\n");
        return sb.toString();
    }

    private static String row(String name, Result r, int duration, List<Long> sorted) {
        double thr = r.ok.sum() / (double) duration;
        if (sorted.isEmpty()) {
            return String.format("| %s | %.0f | %d | - | - | - |%n", name, thr, r.err.sum());
        }
        return String.format("| %s | %.0f | %d | %.2f | %.2f | %.2f |%n",
                name, thr, r.err.sum(),
                sorted.get(percentileIndex(sorted.size(), 0.50)) / 1_000_000.0,
                sorted.get(percentileIndex(sorted.size(), 0.99)) / 1_000_000.0,
                sorted.get(sorted.size() - 1) / 1_000_000.0);
    }

    private static int percentileIndex(int size, double p) {
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
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    try {
                        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long dt = System.nanoTime() - t0;
                        if (resp.statusCode() == 200) {
                            ok.increment();
                            if (!warmup && latencies.size() < 500_000) {
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
