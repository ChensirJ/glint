package io.github.glint.http;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection-private request input.
 *
 * <p>One virtual thread owns the socket, so this stream does not take a monitor
 * across a blocking read. A monitor held over socket I/O pins that carrier on
 * JDK 21. {@link java.io.BufferedInputStream} avoids the pin only when the
 * instance is exactly that class; a small locked buffer also caps how much of
 * a large upload each read pulls in.
 *
 * <p>Ordinary requests use an 8 KiB heap buffer. A body of
 * {@link #LARGE_BODY_BYTES} or more switches the connection onto a reused
 * 1 MiB direct buffer and reads through {@link SocketChannel} so the kernel
 * copies straight into that buffer. One read can take a whole 1 MiB upload
 * once the receive window is wide enough. Bytes already pulled for the next
 * keep-alive request stay in the buffer; they are not discarded.
 */
final class ConnectionInputStream extends InputStream {

    static final int SMALL_BUFFER = 8 * 1024;
    static final int LARGE_BUFFER = 1024 * 1024;
    /** Content-Length at or above this uses the direct buffer. */
    static final long LARGE_BODY_BYTES = 64 * 1024L;

    private static final ConcurrentLinkedQueue<ByteBuffer> DIRECT_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger POOLED = new AtomicInteger();
    private static final int MAX_POOLED = 8;

    private final ReadableByteChannel channel;
    private final InputStream raw;
    private byte[] buf;
    private ByteBuffer heapView;
    private ByteBuffer direct;
    private int pos;
    private int count;
    private boolean large;
    private boolean closed;

    ConnectionInputStream(Socket socket) throws IOException {
        SocketChannel ch = socket.getChannel();
        if (ch != null && !(socket instanceof SSLSocket)) {
            this.channel = ch;
            this.raw = null;
        } else {
            this.channel = null;
            this.raw = socket.getInputStream();
        }
        this.buf = new byte[SMALL_BUFFER];
        this.heapView = channel != null ? ByteBuffer.wrap(buf) : null;
    }

    /** Heap or channel source for tests. Exactly one of the two is non-null. */
    ConnectionInputStream(InputStream raw) {
        this.channel = null;
        this.raw = raw;
        this.buf = new byte[SMALL_BUFFER];
    }

    ConnectionInputStream(ReadableByteChannel channel) {
        this.channel = channel;
        this.raw = null;
        this.buf = new byte[SMALL_BUFFER];
        this.heapView = ByteBuffer.wrap(buf);
    }

    /**
     * Switch this connection to the large-body buffer. Safe to call once
     * headers are parsed and again on later requests (no-op after the first).
     * Tops the buffer up with one socket read so the handler's first
     * {@code read} is not limited to the header lookahead.
     */
    void expectLargeBody() throws IOException {
        ensureOpen();
        if (large) {
            return;
        }
        int avail = Math.max(0, count - pos);
        if (channel != null) {
            ByteBuffer bigger = borrowDirect();
            try {
                bigger.clear();
                if (avail > 0) {
                    bigger.put(buf, pos, avail);
                }
                if (bigger.hasRemaining()) {
                    channel.read(bigger);
                }
                bigger.flip();
            } catch (IOException e) {
                recycle(bigger);
                throw e;
            }
            direct = bigger;
            // Heap cursors no longer own those bytes.
            pos = 0;
            count = 0;
        } else {
            byte[] bigger = new byte[LARGE_BUFFER];
            if (avail > 0) {
                System.arraycopy(buf, pos, bigger, 0, avail);
            }
            int n = raw.read(bigger, avail, bigger.length - avail);
            buf = bigger;
            pos = 0;
            count = avail + Math.max(n, 0);
        }
        large = true;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        if (direct != null) {
            if (!direct.hasRemaining()) {
                int n = fillDirect();
                if (n <= 0) {
                    return -1;
                }
            }
            return direct.get() & 0xff;
        }
        if (pos >= count) {
            int n = fillHeap();
            if (n <= 0) {
                return -1;
            }
        }
        return buf[pos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (direct != null) {
            if (!direct.hasRemaining()) {
                int n = fillDirect();
                if (n <= 0) {
                    return -1;
                }
            }
            int n = Math.min(len, direct.remaining());
            direct.get(b, off, n);
            return n;
        }
        if (pos >= count) {
            int n = fillHeap();
            if (n <= 0) {
                return -1;
            }
        }
        int n = Math.min(len, count - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        if (direct != null && direct.hasRemaining()) {
            return direct.remaining();
        }
        int n = Math.max(0, count - pos);
        if (raw != null) {
            int more = raw.available();
            if (more > 0 && n <= Integer.MAX_VALUE - more) {
                n += more;
            }
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long left = n;
        byte[] trash = new byte[(int) Math.min(left, SMALL_BUFFER)];
        while (left > 0) {
            int r = read(trash, 0, (int) Math.min(trash.length, left));
            if (r < 0) {
                break;
            }
            left -= r;
        }
        return n - left;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        recycle(direct);
        direct = null;
        buf = null;
        heapView = null;
        if (raw != null) {
            raw.close();
        }
    }

    private int fillHeap() throws IOException {
        int n;
        if (channel != null) {
            heapView.clear();
            n = channel.read(heapView);
        } else {
            n = raw.read(buf, 0, buf.length);
        }
        if (n > 0) {
            pos = 0;
            count = n;
            return n;
        }
        pos = 0;
        count = 0;
        return n;
    }

    private int fillDirect() throws IOException {
        direct.clear();
        int n = channel.read(direct);
        if (n > 0) {
            direct.flip();
            return n;
        }
        direct.limit(0);
        return n == 0 ? -1 : n;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    private static ByteBuffer borrowDirect() {
        ByteBuffer pooled = DIRECT_POOL.poll();
        if (pooled != null) {
            POOLED.decrementAndGet();
            pooled.clear();
            return pooled;
        }
        return ByteBuffer.allocateDirect(LARGE_BUFFER);
    }

    private static void recycle(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() != LARGE_BUFFER) {
            return;
        }
        if (POOLED.get() >= MAX_POOLED) {
            return;
        }
        buffer.clear();
        POOLED.incrementAndGet();
        DIRECT_POOL.offer(buffer);
    }
}
