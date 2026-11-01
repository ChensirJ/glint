package io.github.glint.http;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;

/**
 * Connection-private request input.
 *
 * <p>One virtual thread owns the socket, so this stream does not take a monitor
 * across a blocking read. A monitor held over socket I/O pins that carrier on
 * JDK 21. {@link java.io.BufferedInputStream} avoids the pin only when the
 * instance is exactly that class; a small locked buffer also caps how much of
 * a large upload each read pulls in.
 *
 * <p>Ordinary requests use an 8 KiB heap buffer. After
 * {@link #expectLargeBody()}, a handler read of at least that size is filled
 * from the socket straight into the caller's array, in slices of at most
 * {@link #MAX_SOCKET_READ}. On Windows the JDK clamps every {@code WSARecv}
 * to {@code 128 KiB - 1} and does not loop, so a 1 MiB direct buffer still
 * costs a second copy into the CRC buffer and never reduces the syscall count.
 * Bytes already pulled for the next keep-alive request stay in the 8 KiB
 * buffer; a body read never asks for more than the caller requested, which is
 * capped at the remaining content length.
 */
final class ConnectionInputStream extends InputStream {

    static final int SMALL_BUFFER = 8 * 1024;
    /**
     * Receive-window target for a large body ({@code SO_RCVBUF}). Not the
     * size of one socket read.
     */
    static final int LARGE_BUFFER = 1024 * 1024;
    /** Content-Length at or above this uses caller-buffer socket reads. */
    static final long LARGE_BODY_BYTES = 64 * 1024L;
    /**
     * Largest single socket read. Matches the Windows JDK cap
     * ({@code MAX_BUFFER_SIZE = 128 KiB - 1} in {@code SocketDispatcher})
     * and {@code java.net.Socket}'s per-syscall limit. Asking for more is
     * silently truncated on Windows, which looks like a short read.
     */
    static final int MAX_SOCKET_READ = 128 * 1024 - 1;

    private final ReadableByteChannel channel;
    private final InputStream raw;
    private byte[] buf;
    private ByteBuffer heapView;
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
     * Mark this connection as serving a large body. Safe to call once headers
     * are parsed and again on later requests (no-op after the first). Does not
     * pull extra socket bytes: the handler read fills its own array, and any
     * lookahead already in the 8 KiB buffer is consumed first.
     */
    void expectLargeBody() throws IOException {
        ensureOpen();
        large = true;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
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
        if (large && len >= SMALL_BUFFER) {
            return readLarge(b, off, len);
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

    /**
     * Copy any buffered lookahead, then read the socket until {@code len}
     * bytes are in {@code b} or the peer has no more data. Each syscall is
     * capped so Windows does not truncate it into a false short read.
     */
    private int readLarge(byte[] b, int off, int len) throws IOException {
        int copied = copyBuffered(b, off, len);
        while (copied < len) {
            int want = Math.min(len - copied, MAX_SOCKET_READ);
            int n = readSocket(b, off + copied, want);
            if (n < 0) {
                return copied == 0 ? -1 : copied;
            }
            if (n == 0) {
                // A blocking channel read does not return 0. Treat it as EOF
                // so a confused source cannot spin.
                return copied == 0 ? -1 : copied;
            }
            copied += n;
        }
        return copied;
    }

    private int copyBuffered(byte[] b, int off, int len) {
        int avail = count - pos;
        if (avail <= 0 || len <= 0) {
            return 0;
        }
        int n = Math.min(len, avail);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    private int readSocket(byte[] b, int off, int len) throws IOException {
        if (channel != null) {
            return channel.read(ByteBuffer.wrap(b, off, len));
        }
        return raw.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
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

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}
