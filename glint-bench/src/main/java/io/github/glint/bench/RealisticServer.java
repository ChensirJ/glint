package io.github.glint.bench;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Child-process server for {@link RealisticBench}. Runs either Glint or embedded Tomcat
 * (defaults: NIO connector, maxThreads 200) with identical handlers from {@link BenchApp}.
 *
 * <p>Usage: {@code RealisticServer glint|tomcat <rootDir>}. Prints {@code READY <port>}
 * and exits when stdin is closed.</p>
 */
public final class RealisticServer {

    private static final AtomicLong HEAP_PEAK = new AtomicLong();
    private static final AtomicLong THREADS_PEAK = new AtomicLong();

    private RealisticServer() {
    }

    public static void main(String[] args) throws Exception {
        String kind = args[0];
        Path root = Path.of(args[1]);
        Logger.getLogger("").setLevel(Level.WARNING);
        startSampler();
        int port = "glint".equals(kind) ? startGlint(root) : startTomcat(root);
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
                        resp.json(BenchApp.upload(new ByteArrayInputStream(req.bodyBytes()))))
                .get("/_bench/stats", (req, resp) -> resp.text(snapshotAndReset()))
                .staticFiles("/static", root.resolve("static"))
                .startAsync();
        return g.port();
    }

    private static int startTomcat(Path root) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(Files.createTempDirectory("glint-realistic-tomcat").toString());
        Context ctx = tomcat.addContext("", root.toAbsolutePath().toString());
        Tomcat.initWebappDefaults(ctx);
        if (ctx.findChild("default") == null) {
            Tomcat.addServlet(ctx, "default", new DefaultServlet());
            ctx.addServletMappingDecoded("/", "default");
        }
        Tomcat.addServlet(ctx, "api", new ApiServlet());
        ctx.addServletMappingDecoded("/api/*", "api");
        ctx.addServletMappingDecoded("/_bench/*", "api");
        tomcat.getConnector();
        tomcat.start();
        return tomcat.getConnector().getLocalPort();
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
