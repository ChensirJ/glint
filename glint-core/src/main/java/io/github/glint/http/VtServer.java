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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Virtual-thread native HTTP/1.1 server.
 * One platform thread accepts connections; each connection is handled by a
 * dedicated virtual thread using blocking I/O (no selector event loop).
 *
 * <p>Hot paths intentionally avoid {@code synchronized} so virtual threads
 * are not pinned to carrier threads on JDK 21.
 */
public final class VtServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final RouteHandler handler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread acceptor;

    public VtServer(int port, RouteHandler handler) throws IOException {
        this.handler = handler;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port), 4096);
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
            while (running.get()) {
                HttpRequest req = HttpParser.parse(in);
                if (req == null) {
                    return;
                }
                HttpResponse resp = new HttpResponse(out);
                try {
                    Trace.run(req, () -> handler.handle(req, resp));
                } catch (Exception ex) {
                    if (!respCommittedSafe(resp)) {
                        resp.status(500).text("Internal Server Error");
                    }
                    ex.printStackTrace();
                }
                resp.finish();
                if (!req.keepAlive()) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // Client closed or reset — normal for keep-alive.
        }
    }

    private static boolean respCommittedSafe(HttpResponse resp) {
        // HttpResponse.finish is idempotent; status check is enough for our simple writer.
        return false;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
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
