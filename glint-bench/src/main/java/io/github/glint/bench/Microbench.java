package io.github.glint.bench;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight throughput / latency microbenchmark against a local Glint server.
 *
 * <pre>
 *   mvn -pl glint-bench -am exec:java
 *   # or: java -cp ... io.github.glint.bench.Microbench [warmup] [durationSec] [concurrency]
 * </pre>
 *
 * <p>Numbers are machine-local; publish them in README only when you re-run
 * and paste the console summary. For Tomcat comparison, point the same
 * client harness at a Tomcat Hello World on another port (documented in README).
 */
public final class Microbench {

    public static void main(String[] args) throws Exception {
        int warmupSec = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 64;

        Glint glint = Glint.create(0)
                .pinningWatcher(false)
                .config(ServerConfig.builder().idleTimeoutMs(5_000).shutdownWaitMs(1_000).build())
                .get("/hello", (req, resp) -> resp.text("ok"))
                .startAsync();

        int port = glint.port();
        String url = "http://127.0.0.1:" + port + "/hello";
        System.out.printf("Glint microbench port=%d warmup=%ds duration=%ds concurrency=%d%n",
                port, warmupSec, durationSec, concurrency);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        runPhase(client, url, concurrency, warmupSec, true);
        Result r = runPhase(client, url, concurrency, durationSec, false);

        System.out.println("--- Glint results ---");
        System.out.printf("requests=%d  errors=%d%n", r.ok.sum(), r.err.sum());
        System.out.printf("throughput=%.0f req/s%n", r.ok.sum() / (double) durationSec);
        List<Long> sorted = new ArrayList<>(r.latencies);
        Collections.sort(sorted);
        if (!sorted.isEmpty()) {
            System.out.printf("latency p50=%.2f ms  p99=%.2f ms  max=%.2f ms%n",
                    sorted.get(percentileIndex(sorted.size(), 0.50)) / 1_000_000.0,
                    sorted.get(percentileIndex(sorted.size(), 0.99)) / 1_000_000.0,
                    sorted.get(sorted.size() - 1) / 1_000_000.0);
        }

        glint.stop();
    }

    private static int percentileIndex(int size, double p) {
        int i = (int) Math.ceil(size * p) - 1;
        return Math.max(0, Math.min(size - 1, i));
    }

    private static Result runPhase(HttpClient client, String url, int concurrency,
                                   int seconds, boolean warmup) throws Exception {
        LongAdder ok = new LongAdder();
        LongAdder err = new LongAdder();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(Math.min(200_000, concurrency * seconds * 200)));
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<Future<?>> futures = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Connection", "keep-alive")
                        .GET()
                        .build();
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
