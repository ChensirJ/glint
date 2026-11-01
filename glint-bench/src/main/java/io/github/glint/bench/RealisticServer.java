package io.github.glint.bench;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardVirtualThreadExecutor;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Child-process server for {@link RealisticBench}. Runs Glint or embedded Tomcat
 * with identical handlers from {@link BenchApp}.
 *
 * <p>Kinds:</p>
 * <ul>
 *   <li>{@code glint} - virtual-thread-native Glint</li>
 *   <li>{@code tomcat} - embedded Tomcat, default NIO connector, platform thread pool
 *       (maxThreads 200). Unchanged so older results stay reproducible.</li>
 *   <li>{@code tomcat-vt} - the same servlet app and NIO connector as {@code tomcat},
 *       with {@link StandardVirtualThreadExecutor} on the connector. The acceptor and
 *       poller stay platform threads; this is still NIO/Servlet, not virtual-thread-native.</li>
 * </ul>
 *
 * <p>Usage: {@code RealisticServer glint|tomcat|tomcat-vt <rootDir>}. Prints {@code READY <port>}
 * and exits when stdin is closed.</p>
 */
public final class RealisticServer {

    static final String KIND_GLINT = "glint";
    static final String KIND_TOMCAT = "tomcat";
    static final String KIND_TOMCAT_VT = "tomcat-vt";
    static final List<String> KINDS = List.of(KIND_GLINT, KIND_TOMCAT, KIND_TOMCAT_VT);

    private static final AtomicLong HEAP_PEAK = new AtomicLong();
    private static final AtomicLong THREADS_PEAK = new AtomicLong();

    private RealisticServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !KINDS.contains(args[0])) {
            System.out.println("ERROR usage: RealisticServer glint|tomcat|tomcat-vt <rootDir>");
            System.out.flush();
            throw new IllegalArgumentException("usage: RealisticServer glint|tomcat|tomcat-vt <rootDir>");
        }
        String kind = args[0];
        Path root = Path.of(args[1]);
        Logger.getLogger("").setLevel(Level.WARNING);
        startSampler();
        int port = switch (kind) {
            case KIND_GLINT -> startGlint(root);
            case KIND_TOMCAT -> openTomcat(root, false).port;
            case KIND_TOMCAT_VT -> openTomcat(root, true).port;
            default -> throw new IllegalArgumentException(kind);
        };
        System.out.println("READY " + port);
        System.out.flush();
        while (System.in.read() != -1) {
            // block until the parent closes our stdin
        }
        System.exit(0);
    }

    private static int startGlint(Path root) throws IOException {
        Glint g = Glint.create(0)
                .config(ServerConfig.builder().maxRequestBodyBytes(16 * 1024 * 1024).build())
                .get("/api/hello", (req, resp) -> resp.json(BenchApp.hello()))
                .get("/api/slow", (req, resp) -> resp.json(BenchApp.slow()))
                .get("/api/item", (req, resp) -> resp.json(BenchApp.item(req.queryParam("id").orElse("0"))))
                .post("/api/order", (req, resp) -> resp.json(BenchApp.order(req.bodyBytes())))
                .post("/api/upload", (req, resp) ->
                        resp.json(BenchApp.upload(req.bodyStream())))
                .get("/_bench/stats", (req, resp) -> resp.text(snapshotAndReset()))
                .staticFiles("/static", root.resolve("static"))
                .startAsync();
        return g.port();
    }

    /**
     * Embedded Tomcat with the shared servlet app.
     *
     * @param virtualThreads when {@code false}, leave the connector on embedded defaults
     *                        (NIO, platform pool, maxThreads 200). When {@code true}, install
     *                        Tomcat's {@link StandardVirtualThreadExecutor} on that same connector.
     */
    static RunningTomcat openTomcat(Path root, boolean virtualThreads) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(Files.createTempDirectory(
                virtualThreads ? "glint-realistic-tomcat-vt" : "glint-realistic-tomcat").toString());
        Context ctx = tomcat.addContext("", root.toAbsolutePath().toString());
        Tomcat.initWebappDefaults(ctx);
        if (ctx.findChild("default") == null) {
            Tomcat.addServlet(ctx, "default", new DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");
        }
        Tomcat.addServlet(ctx, "api", new ApiServlet());
        ctx.addServletMappingDecoded("/api/*", "api");
        ctx.addServletMappingDecoded("/_bench/*", "api");
        Connector connector = tomcat.getConnector();
        if (virtualThreads) {
            // Registered on the Service so startInternal runs before the connector accepts.
            // execute() throws if the executor has not been started. Acceptor and poller
            // stay platform threads; only request tasks run on virtual threads.
            StandardVirtualThreadExecutor executor = new StandardVirtualThreadExecutor();
            executor.setName("tomcat-vt");
            executor.setNamePrefix("tomcat-vt-");
            tomcat.getService().addExecutor(executor);
            // External executor: Tomcat skips its platform pool. getMaxThreads() then
            // reports -1 (the pool size belongs to the executor). maxThreads stays at
            // the embedded default and is not applied to these virtual threads.
            connector.getProtocolHandler().setExecutor(executor);
        }
        tomcat.start();
        return new RunningTomcat(tomcat, connector.getLocalPort());
    }

    /** Embedded Tomcat handle so tests can stop the server. The bench child keeps it until exit. */
    static final class RunningTomcat implements AutoCloseable {
        final Tomcat tomcat;
        final int port;

        private RunningTomcat(Tomcat tomcat, int port) {
            this.tomcat = tomcat;
            this.port = port;
        }

        @Override
        public void close() throws Exception {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /** Same routes as the Glint side, dispatched by URI. */
    static final class ApiServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String uri = req.getRequestURI();
            String method = req.getMethod();
            String body;
            String type = "application/json";
            try {
                if ("GET".equals(method) && "/api/hello".equals(uri)) {
                    body = BenchApp.hello();
                } else if ("GET".equals(method) && "/api/slow".equals(uri)) {
                    body = BenchApp.slow();
                } else if ("GET".equals(method) && "/api/item".equals(uri)) {
                    String id = req.getParameter("id");
                    body = BenchApp.item(id == null ? "0" : id);
                } else if ("POST".equals(method) && "/api/order".equals(uri)) {
                    body = BenchApp.order(req.getInputStream().readAllBytes());
                } else if ("POST".equals(method) && "/api/upload".equals(uri)) {
                    body = BenchApp.upload(req.getInputStream());
                } else if ("GET".equals(method) && "/_bench/stats".equals(uri)) {
                    body = snapshotAndReset();
                    type = "text/plain";
                } else {
                    resp.sendError(404);
                    return;
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                resp.sendError(500);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            resp.setStatus(200);
            resp.setContentType(type);
            resp.setCharacterEncoding("UTF-8");
            resp.setContentLength(bytes.length);
            resp.getOutputStream().write(bytes);
        }
    }

    private static void startSampler() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Thread t = new Thread(() -> {
            while (true) {
                HEAP_PEAK.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(), Math::max);
                THREADS_PEAK.accumulateAndGet(threads.getThreadCount(), Math::max);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bench-sampler");
        t.setDaemon(true);
        t.start();
    }

    /** Peak heap used / platform threads since the previous call, then reset the peaks. */
    static String snapshotAndReset() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long heapPeak = Math.max(HEAP_PEAK.getAndSet(0), mem.getHeapMemoryUsage().getUsed());
        long thrPeak = Math.max(THREADS_PEAK.getAndSet(0), threads.getThreadCount());
        return "heapPeakMB=" + (heapPeak >> 20)
                + ";heapCommittedMB=" + (mem.getHeapMemoryUsage().getCommitted() >> 20)
                + ";threadsPeak=" + thrPeak
                + ";threadsNow=" + threads.getThreadCount();
    }
}
