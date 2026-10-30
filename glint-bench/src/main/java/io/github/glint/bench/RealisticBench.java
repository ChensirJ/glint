package io.github.glint.bench;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Realistic Glint vs Tomcat suite. Each server runs in its own child JVM with identical
 * flags and identical {@link BenchApp} handlers; this process is the load generator
 * (raw HTTP/1.1 over blocking sockets, one virtual thread per simulated client).
 *
 * <p>Scenarios: blocking downstream sweep, mixed JSON + DB wait + CPU, mixed static/API
 * traffic, keep-alive vs connection-per-request, and 1 MiB uploads.</p>
 *
 * <p>Usage: {@code RealisticBench [rounds=3] [warmupSec=3] [durationSec=8] [scenarioFilter]}.
 * Replaces the {@code ## Realistic suite} section of {@code docs/bench-results.md}.</p>
 */
public final class RealisticBench {

    static final String[] JVM_FLAGS = {"-Xms256m", "-Xmx1g", "-XX:+UseG1GC"};
    static final int SOCKET_TIMEOUT_MS = 10_000;
    /** Failure reasons seen during the current run (printed, not part of the report). */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> REASONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void reason(String kind, Throwable e) {
        REASONS.computeIfAbsent(kind + ": " + e.getClass().getSimpleName() + " " + e.getMessage(),
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    record Req(String label, byte[] bytes, int weight) {
    }

    record Scenario(String group, String title, List<Req> reqs, int concurrency, boolean keepAlive) {
    }

    record ClassStats(String label, long ok, double p50, double p99, double p999) {
    }

    record Run(String server, double rps, long ok, long errors, long connectErrors, long timeouts, double p50, double p99,
               double p999, double max, double mbPerSec, Map<String, String> stats, long rssMb,
               List<ClassStats> classes) {
    }

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int duration = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        Path root = Files.createTempDirectory("glint-realistic");
        Path staticDir = Files.createDirectories(root.resolve("static"));
        Files.writeString(staticDir.resolve("small.css"), ".btn{color:#333;padding:4px 8px}\n".repeat(128));
        byte[] large = new byte[1024 * 1024];
        new Random(42).nextBytes(large);
        Files.write(staticDir.resolve("large.bin"), large);

        String when = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'CST'"));
        Map<String, String> specs = machineSpecs();
        System.out.println("=== Realistic suite (" + when + ") rounds=" + rounds
                + " warmup=" + warmup + "s duration=" + duration + "s ===");
        specs.forEach((k, v) -> System.out.println(k + ": " + v));

        List<Scenario> scenarios = new ArrayList<>(scenarios());
        if (args.length > 3) {
            // Optional filter for quick checks; only full runs belong in the docs.
            scenarios.removeIf(s -> !s.title().contains(args[3]));
        }
        Server glint = Server.start("glint", root);
        Server tomcat = Server.start("tomcat", root);
        Map<Scenario, List<List<Run>>> results = new LinkedHashMap<>();
        try {
            for (Scenario sc : scenarios) {
                List<Run> g = new ArrayList<>();
                List<Run> t = new ArrayList<>();
                for (int r = 0; r < rounds; r++) {
                    // Alternate who goes first so drift (thermal, GC) does not favor one side.
                    Server[] order = r % 2 == 0 ? new Server[]{glint, tomcat} : new Server[]{tomcat, glint};
                    for (Server s : order) {
                        REASONS.clear();
                        Run run = runOnce(s, sc, warmup, duration);
                        REASONS.forEach((k, v) -> System.out.println("    reason x" + v.sum() + " " + k));
                        (s == glint ? g : t).add(run);
                        System.out.printf(Locale.ROOT,
                                "%-14s c=%-5d %-6s r%d: %8.0f req/s p50=%.2f p99=%.2f p999=%.2f err=%d connErr=%d to=%d %s rss=%dMB%n",
                                sc.title(), sc.concurrency(), s.kind, r + 1, run.rps(), run.p50(), run.p99(),
                                run.p999(), run.errors(), run.connectErrors(), run.timeouts(), run.stats(), run.rssMb());
                    }
                }
                results.put(sc, List.of(g, t));
            }
        } finally {
            glint.stop();
            tomcat.stop();
        }
        writeMarkdown(when, specs, rounds, warmup, duration, results);
    }

    static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        Req slow = new Req("api", get("/api/slow", true), 1);
        for (int c : new int[]{100, 500, 1000, 2000, 4000}) {
            list.add(new Scenario("blocking", "blocking-50ms", List.of(slow), c, true));
        }
        Req order = new Req("api", post("/api/order", "application/json", BenchApp.samplePayload(60)), 1);
        for (int c : new int[]{200, 1000}) {
            list.add(new Scenario("json", "json+db+cpu", List.of(order), c, true));
        }
        List<Req> mixed = List.of(
                new Req("api", get("/api/item?id=42", true), 70),
                new Req("static-4KiB", get("/static/small.css", true), 20),
                new Req("static-1MiB", get("/static/large.bin", true), 10));
        list.add(new Scenario("traffic", "mixed-traffic", mixed, 500, true));
        list.add(new Scenario("conn", "keep-alive", List.of(new Req("api", get("/api/hello", true), 1)), 64, true));
        list.add(new Scenario("conn", "conn-per-req", List.of(new Req("api", get("/api/hello", false), 1)), 64, false));
        byte[] blob = new byte[1024 * 1024];
        new Random(7).nextBytes(blob);
        list.add(new Scenario("upload", "upload-1MiB",
                List.of(new Req("api", post("/api/upload", "application/octet-stream", blob), 1)), 64, true));
        return list;
    }

    static byte[] get(String path, boolean keepAlive) {
        return ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept-Encoding: identity\r\n"
                + (keepAlive ? "" : "Connection: close\r\n") + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] post(String path, String type, byte[] body) {
        byte[] head = ("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] all = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    // ------------------------------------------------------------------ load generator

    /** Per-worker counters; merged after the run. */
    static final class WorkerStats {
        final int[][] lat;
        final int[] n;
        long errors;
        long connectErrors;
        long timeouts;
        long bytes;

        WorkerStats(int classes) {
            lat = new int[classes][1024];
            n = new int[classes];
        }

        void add(int k, long nanos) {
            if (n[k] == lat[k].length) {
                lat[k] = Arrays.copyOf(lat[k], lat[k].length * 2);
            }
            lat[k][n[k]++] = (int) Math.min(Integer.MAX_VALUE, nanos / 1000);
        }
    }

    static Run runOnce(Server s, Scenario sc, int warmupSec, int durationSec) throws Exception {
        // One continuous run: connections opened during warmup are reused for the measured window.
        LoadResult lr = load(s, sc, warmupSec, durationSec);
        Map<String, String> stats = s.stats();
        long rss = s.rssMb();
        return lr.toRun(s.kind, stats, rss, sc);
    }

    record LoadResult(List<WorkerStats> workers, double seconds) {
        Run toRun(String server, Map<String, String> stats, long rss, Scenario sc) {
            int classes = sc.reqs().size();
            long errors = 0;
            long connectErrors = 0;
            long timeouts = 0;
            long bytes = 0;
            List<int[]> perClass = new ArrayList<>();
            int total = 0;
            for (int k = 0; k < classes; k++) {
                int cnt = 0;
                for (WorkerStats w : workers) {
                    cnt += w.n[k];
                }
                int[] all = new int[cnt];
                int pos = 0;
                for (WorkerStats w : workers) {
                    System.arraycopy(w.lat[k], 0, all, pos, w.n[k]);
                    pos += w.n[k];
                }
                Arrays.sort(all);
                perClass.add(all);
                total += cnt;
            }
            for (WorkerStats w : workers) {
                errors += w.errors;
                connectErrors += w.connectErrors;
                timeouts += w.timeouts;
                bytes += w.bytes;
            }
            int[] merged = new int[total];
            int pos = 0;
            for (int[] a : perClass) {
                System.arraycopy(a, 0, merged, pos, a.length);
                pos += a.length;
            }
            Arrays.sort(merged);
            List<ClassStats> cs = new ArrayList<>();
            if (classes > 1) {
                for (int k = 0; k < classes; k++) {
                    int[] a = perClass.get(k);
                    cs.add(new ClassStats(sc.reqs().get(k).label(), a.length, pct(a, 0.50), pct(a, 0.99), pct(a, 0.999)));
                }
            }
            return new Run(server, total / seconds, total, errors, connectErrors, timeouts, pct(merged, 0.50), pct(merged, 0.99),
                    pct(merged, 0.999), merged.length == 0 ? 0 : merged[merged.length - 1] / 1000.0,
                    bytes / seconds / (1024 * 1024), stats, rss, cs);
        }
    }

    static double pct(int[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))] / 1000.0;
    }

    static LoadResult load(Server server, Scenario sc, int warmupSec, int seconds) throws Exception {
        int port = server.port;
        boolean record = true;
        int c = sc.concurrency();
        List<Req> reqs = sc.reqs();
        int[] cumulative = new int[reqs.size()];
        int sum = 0;
        for (int k = 0; k < reqs.size(); k++) {
            sum += reqs.get(k).weight();
            cumulative[k] = sum;
        }
        final int weightSum = sum;
        // Ramp clients in over (c / 100) * 5 ms; only requests started after the ramp are recorded.
        long rampNanos = (c / 100 + 1) * 5_000_000L;
        long launch = System.nanoTime();
        long measureStart = launch + rampNanos + warmupSec * 1_000_000_000L;
        long deadline = measureStart + seconds * 1_000_000_000L;
        List<WorkerStats> workers = new ArrayList<>(c);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ex.submit(() -> {
                // Reset server-side heap/thread peaks at the warmup -> measure boundary.
                long wait = measureStart - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                server.stats();
                return null;
            });
            for (int i = 0; i < c; i++) {
                WorkerStats ws = new WorkerStats(reqs.size());
                workers.add(ws);
                long seed = i * 7919L + 1;
                ex.submit(() -> {
                    worker(port, sc, reqs, cumulative, weightSum, seed, ws, record, measureStart, deadline);
                    return null;
                });
                if (i % 100 == 99) {
                    Thread.sleep(5);
                }
            }
        }
        return new LoadResult(workers, (deadline - measureStart) / 1e9);
    }

    static void worker(int port, Scenario sc, List<Req> reqs, int[] cumulative, int weightSum, long seed,
                       WorkerStats ws, boolean record, long measureStart, long deadline) {
        SplittableRandom rnd = new SplittableRandom(seed);
        Conn conn = null;
        while (true) {
            long t0 = System.nanoTime();
            if (t0 >= deadline) {
                break;
            }
            int k = 0;
            if (reqs.size() > 1) {
                int x = rnd.nextInt(weightSum);
                while (x >= cumulative[k]) {
                    k++;
                }
            }
            boolean counted = record && t0 >= measureStart;
            if (conn == null) {
                try {
                    conn = Conn.open(port);
                } catch (IOException e) {
                    if (counted) {
                        ws.connectErrors++;
                        reason("connect", e);
                    }
                    sleepQuietly();
                    continue;
                }
            }
            try {
                int status = conn.exchange(reqs.get(k).bytes());
                long t1 = System.nanoTime();
                if (counted && t1 <= deadline) {
                    if (status == 200) {
                        ws.add(k, t1 - t0);
                        ws.bytes += conn.lastBodyBytes;
                    } else {
                        ws.errors++;
                    }
                }
                if (!sc.keepAlive() || conn.serverClosed) {
                    conn.drainAndClose();
                    conn = null;
                }
            } catch (SocketTimeoutException e) {
                if (counted) {
                    ws.timeouts++;
                }
                conn = closeQuietly(conn);
            } catch (IOException | RuntimeException e) {
                if (counted) {
                    ws.errors++;
                    reason("io", e);
                }
                conn = closeQuietly(conn);
                sleepQuietly();
            }
        }
        closeQuietly(conn);
    }

    static void sleepQuietly() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    static Conn closeQuietly(Conn c) {
        if (c != null) {
            try {
                c.socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        return null;
    }

    /** Minimal HTTP/1.1 client connection: Content-Length, chunked, and read-to-close bodies. */
    static final class Conn {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final byte[] buf = new byte[32 * 1024];
        int pos;
        int lim;
        boolean serverClosed;
        long lastBodyBytes;

        private Conn(Socket s) throws IOException {
            this.socket = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
        }

        static Conn open(int port) throws IOException {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            // RST on close: tens of thousands of client sockets per suite would otherwise exhaust
            // the ephemeral port range with TIME_WAIT entries on Windows.
            s.setSoLinger(true, 0);
            s.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            return new Conn(s);
        }

        int exchange(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            String status = readLine();
            if (status == null) {
                throw new EOFException("connection closed before status line");
            }
            int code = Integer.parseInt(status.substring(9, 12));
            long cl = -1;
            boolean chunked = false;
            serverClosed = false;
            String line;
            while ((line = readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("content-length")) {
                    cl = Long.parseLong(value);
                } else if (name.equalsIgnoreCase("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                } else if (name.equalsIgnoreCase("connection") && value.equalsIgnoreCase("close")) {
                    serverClosed = true;
                }
            }
            long body = 0;
            if (chunked) {
                while (true) {
                    String sizeLine = readLine();
                    if (sizeLine == null) {
                        throw new EOFException("eof in chunked body");
                    }
                    int semi = sizeLine.indexOf(';');
                    long size = Long.parseLong((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
                    if (size == 0) {
                        while ((line = readLine()) != null && !line.isEmpty()) {
                            // trailers
                        }
                        break;
                    }
                    skip(size);
                    body += size;
                    readLine();
                }
            } else if (cl >= 0) {
                skip(cl);
                body = cl;
            } else {
                while (fill() > 0) {
                    body += lim - pos;
                    pos = lim;
                }
                serverClosed = true;
            }
            lastBodyBytes = body;
            return code;
        }

        /** For Connection: close, wait for the server FIN, then reset. */
        void drainAndClose() {
            try {
                socket.setSoTimeout(1_000);
                while (fill() > 0) {
                    pos = lim;
                }
            } catch (IOException ignored) {
                // closing anyway
            }
            closeQuietly(this);
        }

        private int fill() throws IOException {
            int r = in.read(buf);
            if (r <= 0) {
                pos = 0;
                lim = 0;
                return -1;
            }
            pos = 0;
            lim = r;
            return r;
        }

        private String readLine() throws IOException {
            StringBuilder sb = null;
            while (true) {
                if (pos >= lim && fill() < 0) {
                    return sb == null ? null : sb.toString();
                }
                int start = pos;
                while (pos < lim) {
                    if (buf[pos] == '\n') {
                        int end = pos;
                        pos++;
                        if (end > start && buf[end - 1] == '\r') {
                            end--;
                        }
                        String part = new String(buf, start, end - start, StandardCharsets.ISO_8859_1);
                        if (sb == null) {
                            return part;
                        }
                        // a CR may sit at the end of the previous chunk
                        if (part.isEmpty() && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                            sb.setLength(sb.length() - 1);
                        }
                        return sb.append(part).toString();
                    }
                    pos++;
                }
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(new String(buf, start, pos - start, StandardCharsets.ISO_8859_1));
            }
        }

        private void skip(long n) throws IOException {
            while (n > 0) {
                if (pos >= lim && fill() < 0) {
                    throw new EOFException("eof in body");
                }
                int take = (int) Math.min(n, lim - pos);
                pos += take;
                n -= take;
            }
        }
    }

    // ------------------------------------------------------------------ child server

    static final class Server {
        final String kind;
        final Process process;
        final int port;

        private Server(String kind, Process p, int port) {
            this.kind = kind;
            this.process = p;
            this.port = port;
        }

        static Server start(String kind, Path root) throws IOException {
            String java = ProcessHandle.current().info().command().orElse("java");
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.addAll(List.of(JVM_FLAGS));
            // -Dglint.bench.serverFlags="..." adds server JVM flags (for example JFR) to both sides.
            String extra = System.getProperty("glint.bench.serverFlags", "").trim();
            if (!extra.isEmpty()) {
                cmd.addAll(List.of(extra.replace("{kind}", kind).split("\\s+")));
            }
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(RealisticServer.class.getName());
            cmd.add(kind);
            cmd.add(root.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("READY ")) {
                    int port = Integer.parseInt(line.substring(6).trim());
                    Thread drain = new Thread(() -> {
                        try {
                            while (r.readLine() != null) {
                                // discard
                            }
                        } catch (IOException ignored) {
                            // child gone
                        }
                    });
                    drain.setDaemon(true);
                    drain.start();
                    return new Server(kind, p, port);
                }
            }
            throw new IOException(kind + " child exited before READY");
        }

        /** Peak heap / threads since the previous call (the call resets the peaks). */
        Map<String, String> stats() throws IOException {
            return fetchStatsText();
        }

        private Map<String, String> fetchStatsText() throws IOException {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(SOCKET_TIMEOUT_MS);
                s.setSoLinger(true, 0);
                s.getOutputStream().write(get("/_bench/stats", false));
                String all = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                String body = all.substring(all.indexOf("\r\n\r\n") + 4).trim();
                Map<String, String> m = new LinkedHashMap<>();
                for (String kv : body.split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        m.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
                return m;
            }
        }

        /** Resident set size of the child, in MiB (Windows tasklist or Linux /proc). */
        long rssMb() {
            long pid = process.pid();
            try {
                if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                    Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH").start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1).trim();
                    String[] cols = out.split("\",\"");
                    String mem = cols[cols.length - 1].replaceAll("[^0-9]", "");
                    return Long.parseLong(mem) / 1024;
                }
                for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                    if (l.startsWith("VmRSS:")) {
                        return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                    }
                }
            } catch (Exception ignored) {
                // unknown
            }
            return -1;
        }

        void stop() {
            try {
                process.getOutputStream().close();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------ report

    static Map<String, String> machineSpecs() {
        Map<String, String> m = new LinkedHashMap<>();
        String cpu = System.getenv("PROCESSOR_IDENTIFIER");
        try {
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name").start();
                String name = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) {
                    cpu = name;
                }
            } else {
                for (String l : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (l.startsWith("model name")) {
                        cpu = l.substring(l.indexOf(':') + 1).trim();
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // keep env fallback
        }
        m.put("CPU", cpu == null ? "unknown" : cpu);
        m.put("Logical cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        long ram = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            ram = os.getTotalMemorySize() >> 20;
        }
        m.put("RAM", ram < 0 ? "unknown" : String.format(Locale.ROOT, "%.1f GiB", ram / 1024.0));
        m.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        m.put("JDK", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        m.put("Tomcat", org.apache.catalina.util.ServerInfo.getServerNumber()
                + " (embedded, default NIO connector: maxThreads 200, acceptCount 100)");
        m.put("Server JVM flags", String.join(" ", JVM_FLAGS) + " (same for both, separate child JVMs)");
        return m;
    }

    static Run median(List<Run> runs) {
        List<Run> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingDouble(Run::rps));
        return sorted.get(sorted.size() / 2);
    }

    static void writeMarkdown(String when, Map<String, String> specs, int rounds, int warmup, int duration,
                              Map<Scenario, List<List<Run>>> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Realistic suite (Glint vs Tomcat, same app logic)\n\n");
        sb.append("- Captured: **").append(when).append("** (Asia/Shanghai)\n");
        specs.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        sb.append("- Load generator: `RealisticBench`, raw HTTP/1.1 over blocking sockets, one virtual thread per ")
                .append("client, closed loop, same machine (loopback), separate JVM from both servers\n");
        sb.append("- Each cell: warmup ").append(warmup).append("s + measure ").append(duration)
                .append("s, ").append(rounds).append(" rounds alternating server order; ")
                .append("the median round by req/s is shown with the min-max req/s across rounds\n");
        sb.append("- Handlers: identical `BenchApp` code on both servers; Glint uses default settings except ")
                .append("maxRequestBodyBytes 16 MiB (for uploads); Tomcat uses embedded defaults\n");
        sb.append("- Heap peak and platform-thread peak are sampled every 50 ms inside the server JVM during the ")
                .append("measured window; RSS is the process working set right after it\n");
        sb.append("- Re-run: `powershell -File scripts/run-bench.ps1` (all suites) or see the script for the ")
                .append("single `java -cp ... RealisticBench 3 3 8` command\n\n");
        sb.append("Scenarios:\n\n");
        sb.append("- blocking-50ms: `GET /api/slow`, handler sleeps ").append(BenchApp.SLOW_MS)
                .append(" ms (slow downstream), keep-alive\n");
        sb.append("- json+db+cpu: `POST /api/order` with a ").append(BenchApp.samplePayload(60).length / 1024)
                .append(" KiB JSON order (60 lines): parse, price lines, SHA-256 x5 over the body, sleep ")
                .append(BenchApp.ORDER_DB_MS).append(" ms, serialize a ~5 KiB JSON response\n");
        sb.append("- mixed-traffic: 70% `GET /api/item` (").append(BenchApp.ITEM_DB_MS)
                .append(" ms wait + 1 KiB JSON), 20% 4 KiB static CSS, 10% 1 MiB static file\n");
        sb.append("- keep-alive vs conn-per-req: `GET /api/hello` tiny JSON; conn-per-req sends ")
                .append("`Connection: close` and opens a new TCP connection for every request\n");
        sb.append("- upload-1MiB: `POST /api/upload` with a 1 MiB body; handler streams via bodyStream() and returns CRC32\n\n");
        sb.append("| Scenario | Clients | Server | req/s (median) | req/s min-max | p50 ms | p99 ms | p99.9 ms | errors | connect errors | timeouts | heap peak MiB | RSS MiB | platform threads peak |\n");
        sb.append("|----------|--------:|--------|---------------:|---------------|-------:|-------:|---------:|-------:|---------------:|---------:|--------------:|--------:|----------------------:|\n");
        StringBuilder classes = new StringBuilder();
        for (Map.Entry<Scenario, List<List<Run>>> e : results.entrySet()) {
            Scenario sc = e.getKey();
            for (List<Run> runs : e.getValue()) {
                Run m = median(runs);
                double min = runs.stream().mapToDouble(Run::rps).min().orElse(0);
                double max = runs.stream().mapToDouble(Run::rps).max().orElse(0);
                sb.append(String.format(Locale.ROOT,
                        "| %s | %d | %s | %.0f | %.0f-%.0f | %.2f | %.2f | %.2f | %d | %d | %d | %s | %d | %s |%n",
                        sc.title(), sc.concurrency(), m.server().equals("glint") ? "Glint" : "Tomcat",
                        m.rps(), min, max, m.p50(), m.p99(), m.p999(), m.errors(), m.connectErrors(), m.timeouts(),
                        m.stats().getOrDefault("heapPeakMB", "?"), m.rssMb(),
                        m.stats().getOrDefault("threadsPeak", "?")));
                for (ClassStats cs : m.classes()) {
                    classes.append(String.format(Locale.ROOT, "| %s | %s | %d | %.2f | %.2f | %.2f |%n",
                            m.server().equals("glint") ? "Glint" : "Tomcat", cs.label(), cs.ok(),
                            cs.p50(), cs.p99(), cs.p999()));
                }
            }
        }
        if (classes.length() > 0) {
            sb.append("\nMixed-traffic breakdown (median round):\n\n");
            sb.append("| Server | Class | ok requests | p50 ms | p99 ms | p99.9 ms |\n");
            sb.append("|--------|-------|------------:|-------:|-------:|---------:|\n");
            sb.append(classes);
        }
        sb.append("\nerrors = reset/EOF/non-200 on an established connection; connect errors = failed TCP connect ")
                .append("(e.g. accept backlog full); timeouts = no response within ").append(SOCKET_TIMEOUT_MS / 1000)
                .append(" s. The generator closes sockets with SO_LINGER=0 (RST) to avoid client TIME_WAIT exhaustion.\n");
        sb.append("\nCaveats: closed-loop load (a slow server also slows the generator, so tail latency is ")
                .append("understated versus an open-loop tool such as wrk2); generator and server share the same CPU; ")
                .append("Glint streams request bodies via bodyStream()/bodyChannel() (bodyBytes() still buffers when called); ")
                .append("platform-thread counts exclude Glint's virtual threads by design; ")
                .append("simulated waits use Thread.sleep, and on Windows JDK 21 virtual-thread timed sleeps are ")
                .append("coarser under load than platform-thread sleeps, which penalizes Glint in sleep-heavy cells.\n");

        // -Dglint.bench.out=<file> writes elsewhere (for before/after captures).
        Path out = Path.of(System.getProperty("glint.bench.out", "docs/bench-results.md"));
        String existing = Files.exists(out) ? Files.readString(out) : "# Glint benchmarks\n";
        int cut = existing.indexOf("\n## Realistic suite");
        String previousSection = cut >= 0 ? existing.substring(cut + 1) : "";
        if (cut >= 0) {
            existing = existing.substring(0, cut);
        }
        String section = sb.toString();
        // Filtered runs only replace matching scenario rows so other cells stay.
        if (!previousSection.isEmpty() && results.size() < scenarios().size()) {
            section = mergeRealisticSection(previousSection, section, results.keySet());
        }
        Files.writeString(out, existing.stripTrailing() + "\n" + section);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    /**
     * Keep rows from {@code previous} whose scenario title was not re-measured.
     * Header, scenario blurb, caveats, and any breakdown tables come from {@code previous},
     * except the Captured line and the buffering caveat which are refreshed from {@code fresh}.
     */
    static String mergeRealisticSection(String previous, String fresh,
                                        java.util.Set<Scenario> updated) {
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (Scenario s : updated) {
            titles.add(s.title());
        }
        java.util.Map<String, java.util.List<String>> freshRows = new java.util.LinkedHashMap<>();
        for (String line : fresh.split("\n", -1)) {
            if (!isMainResultRow(line)) {
                continue;
            }
            String title = tableTitle(line);
            if (title == null || !titles.contains(title)) {
                continue;
            }
            freshRows.computeIfAbsent(title, k -> new java.util.ArrayList<>()).add(line);
        }
        String captured = null;
        for (String line : fresh.split("\n", -1)) {
            if (line.startsWith("- Captured:")) {
                captured = line;
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        boolean inMainTable = false;
        boolean mainHeaderSeen = false;
        for (String line : previous.split("\n", -1)) {
            if (captured != null && line.startsWith("- Captured:")) {
                out.append(captured).append('\n');
                continue;
            }
            if (line.contains("Glint buffers request bodies") || line.contains("Glint streams request bodies")) {
                String caveatLine = null;
                for (String fl : fresh.split("\n", -1)) {
                    if (fl.contains("Glint streams request bodies") || fl.contains("Glint buffers request bodies")) {
                        caveatLine = fl;
                        break;
                    }
                }
                out.append(caveatLine != null ? caveatLine : line).append('\n');
                continue;
            }
            if (line.contains("handler reads it fully and returns CRC32")
                    || line.contains("handler streams via bodyStream()")) {
                String uploadBlurb = null;
                for (String fl : fresh.split("\n", -1)) {
                    if (fl.contains("upload-1MiB:")) {
                        uploadBlurb = fl;
                        break;
                    }
                }
                out.append(uploadBlurb != null ? uploadBlurb : line).append('\n');
                continue;
            }
            if (line.startsWith("| Scenario |")) {
                inMainTable = true;
                mainHeaderSeen = true;
                out.append(line).append('\n');
                continue;
            }
            if (inMainTable && line.startsWith("|---")) {
                out.append(line).append('\n');
                continue;
            }
            if (inMainTable && isMainResultRow(line)) {
                String title = tableTitle(line);
                if (title != null && titles.contains(title)) {
                    if (freshRows.containsKey(title)) {
                        for (String fr : freshRows.remove(title)) {
                            out.append(fr).append('\n');
                        }
                    }
                    continue;
                }
                out.append(line).append('\n');
                continue;
            }
            if (inMainTable) {
                for (java.util.List<String> rows : freshRows.values()) {
                    for (String fr : rows) {
                        out.append(fr).append('\n');
                    }
                }
                freshRows.clear();
                inMainTable = false;
            }
            out.append(line).append('\n');
        }
        if (inMainTable) {
            for (java.util.List<String> rows : freshRows.values()) {
                for (String fr : rows) {
                    out.append(fr).append('\n');
                }
            }
        }
        if (!mainHeaderSeen) {
            return fresh.endsWith("\n") ? fresh : fresh + "\n";
        }
        String s = out.toString();
        return s.endsWith("\n") ? s : s + "\n";
    }

    /** True for data rows of the main Scenario/Clients/Server table (not breakdown). */
    static boolean isMainResultRow(String line) {
        if (!line.startsWith("| ") || line.startsWith("| Scenario") || line.startsWith("|---")) {
            return false;
        }
        String title = tableTitle(line);
        if (title == null || title.isEmpty()) {
            return false;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length < 5) {
            return false;
        }
        String server = parts[3].trim();
        return server.equals("Glint") || server.equals("Tomcat");
    }

    static String tableTitle(String row) {
        if (!row.startsWith("| ")) {
            return null;
        }
        int a = row.indexOf('|', 1);
        if (a < 0) {
            return null;
        }
        return row.substring(1, a).trim();
    }
}
