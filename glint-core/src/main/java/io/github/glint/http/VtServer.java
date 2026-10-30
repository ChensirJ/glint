package io.github.glint.http;

import io.github.glint.obs.Trace;
import io.github.glint.router.RouteHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Virtual-thread native HTTP/1.1 server.
 * One platform thread accepts connections; each connection is handled by a
 * dedicated virtual thread using blocking I/O.
 *
 * <p>Hot paths intentionally avoid {@code synchronized} so virtual threads
 * are not pinned to carrier threads on JDK 21.
 *
 * <p>{@link #close()} stops accepting, then waits for in-flight requests to
 * drain up to {@link ServerConfig#shutdownWaitMs()}.
 */
public final class VtServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final RouteHandler handler;
    private final ServerConfig config;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private Thread acceptor;

    public VtServer(int port, RouteHandler handler) throws IOException {
        this(port, handler, ServerConfig.defaults());
    }

    public VtServer(int port, RouteHandler handler, ServerConfig config) throws IOException {
        this.handler = handler;
        this.config = config == null ? ServerConfig.defaults() : config;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port), this.config.backlog());
    }

    public void start() {
        acceptor = Thread.ofPlatform().name("glint-acceptor").start(() -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    virtualThreads.submit(() -> serve(socket));
                } catch (SocketException se) {
                    if (running.get()) {
                        se.printStackTrace();
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void serve(Socket socket) {
        try (socket;
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setTcpNoDelay(true);
            if (config.idleTimeoutMs() > 0) {
                socket.setSoTimeout(config.idleTimeoutMs());
            }
            while (running.get()) {
                final HttpRequest req;
                try {
                    req = HttpParser.parse(in, config);
                } catch (HttpException he) {
                    HttpResponse err = new HttpResponse(out);
                    writeError(err, he.status(), he.getMessage());
                    err.finish();
                    return;
                } catch (SocketTimeoutException timeout) {
                    return; // idle keep-alive expired
                }
                if (req == null) {
                    return;
                }
                HttpResponse resp = new HttpResponse(out);
                if (req.method() == HttpMethod.HEAD) {
                    resp.suppressBody(true);
                }
                inFlight.incrementAndGet();
                Trace.noteActive(+1);
                try {
                    Trace.run(req, () -> handler.handle(req, resp));
                } catch (HttpException he) {
                    if (!resp.committed()) {
                        writeError(resp, he.status(), he.getMessage());
                    }
                } catch (Exception ex) {
                    if (!resp.committed()) {
                        writeError(resp, 500, "Internal Server Error");
                    }
                    ex.printStackTrace();
                } finally {
                    inFlight.decrementAndGet();
                    Trace.noteActive(-1);
                }
                try {
                    resp.finish(req.method());
                } catch (IOException ioe) {
                    return;
                }
                if (!req.keepAlive() || !running.get()) {
                    return;
                }
            }
        } catch (SocketTimeoutException ignored) {
            // idle timeout between requests
        } catch (IOException ignored) {
            // Client closed or reset — normal for keep-alive.
        }
    }

    private static void writeError(HttpResponse resp, int status, String message) {
        String safe = message == null ? HttpResponse.reason(status) : message;
        // Escape for JSON
        StringBuilder esc = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            if (c == '"' || c == '\\') {
                esc.append('\\');
            }
            if (c == '\n') {
                esc.append("\\n");
            } else if (c == '\r') {
                esc.append("\\r");
            } else {
                esc.append(c);
            }
        }
        resp.status(status).json("{\"error\":\"" + esc + "\",\"status\":" + status + "}");
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public ServerConfig config() {
        return config;
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // continue drain
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownWaitMs());
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        virtualThreads.close();
        if (acceptor != null) {
            try {
                acceptor.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
