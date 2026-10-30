package io.github.glint.http;

import io.github.glint.http.websocket.WebSocketSupport;
import io.github.glint.obs.Trace;
import io.github.glint.router.RouteHandler;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import javax.net.ssl.SSLSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>When a registered WebSocket route matches an Upgrade request, the
 * connection is handed to {@link WebSocketSupport} and leaves the HTTP
 * keep-alive loop.
 */
public final class VtServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final RouteHandler handler;
    private final ServerConfig config;
    private final WebSocketSupport.Registry webSockets;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private Thread acceptor;

    public VtServer(int port, RouteHandler handler) throws IOException {
        this(port, handler, ServerConfig.defaults(), null);
    }

    public VtServer(int port, RouteHandler handler, ServerConfig config) throws IOException {
        this(port, handler, config, null);
    }

    public VtServer(int port, RouteHandler handler, ServerConfig config,
                    WebSocketSupport.Registry webSockets) throws IOException {
        this.handler = handler;
        this.config = config == null ? ServerConfig.defaults() : config;
        this.webSockets = webSockets;
        this.serverSocket = createServerSocket(port, this.config);
    }

    private static ServerSocket createServerSocket(int port, ServerConfig config) throws IOException {
        ServerSocket ss;
        if (config.sslContext() != null) {
            SSLServerSocketFactory factory = config.sslContext().getServerSocketFactory();
            SSLServerSocket ssl = (SSLServerSocket) factory.createServerSocket();
            ssl.setReuseAddress(true);
            ssl.bind(new InetSocketAddress(port), config.backlog());
            ss = ssl;
        } else {
            // Open through NIO so accepted sockets expose a SocketChannel: static files can
            // then be sent with FileChannel.transferTo (sendfile / TransmitFile). The
            // socket adaptor keeps the blocking stream API used by the rest of the server.
            ss = ServerSocketChannel.open().socket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), config.backlog());
        }
        return ss;
    }

    /** Plain-TCP sockets accepted via NIO expose a channel; TLS never does zero-copy. */
    private static WritableByteChannel zeroCopyChannel(Socket socket) {
        if (socket instanceof SSLSocket) {
            return null;
        }
        SocketChannel ch = socket.getChannel();
        return ch != null && ch.isBlocking() ? ch : null;
    }

    public void start() {
        acceptor = Thread.ofPlatform().name("glint-acceptor").start(() -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    openSockets.add(socket);
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
                    HttpResponse err = new HttpResponse(out, zeroCopyChannel(socket));
                    writeError(err, he.status(), he.getMessage());
                    err.finish();
                    return;
                } catch (SocketTimeoutException timeout) {
                    return;
                }
                if (req == null) {
                    return;
                }

                // WebSocket upgrade takes over the socket for the rest of its life.
                try {
                    if (WebSocketSupport.maybeUpgrade(webSockets, req, socket, in, out)) {
                        return;
                    }
                } catch (Exception wsEx) {
                    wsEx.printStackTrace();
                    return;
                }

                HttpResponse resp = new HttpResponse(out, zeroCopyChannel(socket));
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
            // Client closed, reset, or server forced close during shutdown.
        } finally {
            openSockets.remove(socket);
        }
    }

    private static void writeError(HttpResponse resp, int status, String message) {
        String safe = message == null ? HttpResponse.reason(status) : message;
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
        for (Socket s : openSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        openSockets.clear();

        long waitMs = Math.min(config.shutdownWaitMs(), 2_000L);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        virtualThreads.shutdownNow();
        try {
            virtualThreads.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acceptor != null) {
            try {
                acceptor.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
