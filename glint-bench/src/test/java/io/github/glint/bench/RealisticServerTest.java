package io.github.glint.bench;

import org.apache.catalina.core.StandardVirtualThreadExecutor;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Embedded Tomcat modes used by the realistic suite. */
class RealisticServerTest {

    @TempDir
    Path root;

    @Test
    void platformModeKeepsDefaultNioPool() throws Exception {
        try (RealisticServer.RunningTomcat server = RealisticServer.openTomcat(root, false)) {
            Http11NioProtocol protocol = nio(server);
            assertEquals(200, protocol.getMaxThreads());
            assertEquals(100, protocol.getAcceptCount());
            assertInstanceOf(ThreadPoolExecutor.class, protocol.getExecutor());
            assertFalse(protocol.getExecutor() instanceof StandardVirtualThreadExecutor);
            assertFalse(runsOnVirtualThread(protocol.getExecutor()));
            assertHello(server.port);
        }
    }

    @Test
    void virtualThreadModeUsesStandardVirtualThreadExecutorOnNio() throws Exception {
        try (RealisticServer.RunningTomcat server = RealisticServer.openTomcat(root, true)) {
            Http11NioProtocol protocol = nio(server);
            // External executor: Tomcat reports -1 instead of the unused maxThreads default.
            assertEquals(-1, protocol.getMaxThreads());
            assertEquals(100, protocol.getAcceptCount());
            assertInstanceOf(StandardVirtualThreadExecutor.class, protocol.getExecutor());
            assertTrue(runsOnVirtualThread(protocol.getExecutor()));
            assertHello(server.port);
            HttpResponse<String> stats = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port + "/_bench/stats")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stats.statusCode());
            assertTrue(stats.body().contains("threadsPeak="));
            assertTrue(stats.body().contains("heapPeakMB="));
        }
    }

    private static Http11NioProtocol nio(RealisticServer.RunningTomcat server) {
        ProtocolHandler handler = server.tomcat.getConnector().getProtocolHandler();
        assertInstanceOf(Http11NioProtocol.class, handler);
        return (Http11NioProtocol) handler;
    }

    private static void assertHello(int port) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/hello")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(BenchApp.hello(), response.body());
    }

    private static boolean runsOnVirtualThread(java.util.concurrent.Executor executor) throws InterruptedException {
        AtomicBoolean virtual = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> {
            virtual.set(Thread.currentThread().isVirtual());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return virtual.get();
    }
}
