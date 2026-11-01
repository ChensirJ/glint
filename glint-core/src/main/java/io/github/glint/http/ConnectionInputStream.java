package io.github.glint.http;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

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
    /** Reused line scratch for the header parser. Not the socket buffer. */
    private byte[] lineBuf = new byte[160];
    /**
     * Bytes of the last empty-body header block on this connection, and the
     * request parsed from those exact bytes. Keep-alive clients repeat the
     * same request; a match skips the parse and the objects it allocated.
     */
    private byte[] repeatedHeaders;
    private HttpRequest repeatedRequest;
    private int fillEpoch;
    private boolean marking;
    private int headerMark = -1;
    private int headerEpoch = -1;

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

    /**
     * Read one header line into {@link #headerLineBuffer()} without copying
     * through a {@code StringBuilder}. The CRLF is stripped. Bytes past the
     * line stay in the socket buffer for the body or the next keep-alive request.
     *
     * @return line length, or {@code -1} when the peer closed before any byte
     */
    int readHeaderLine(int maxLen) throws IOException {
        ensureOpen();
        int len = 0;
        boolean any = false;
        while (true) {
            if (pos >= count) {
                int n = fillHeap();
                if (n <= 0) {
                    if (!any) {
                        return -1;
                    }
                    return len;
                }
            }
            int c = buf[pos++] & 0xff;
            any = true;
            if (c == '\n') {
                if (len > 0 && lineBuf[len - 1] == '\r') {
                    len--;
                }
                return len;
            }
            if (len == lineBuf.length) {
                int cap = lineBuf.length * 2;
                if (cap > maxLen + 1) {
                    cap = maxLen + 1;
                }
                if (cap <= lineBuf.length) {
                    throw new HttpException(400, "Header line too long");
                }
                lineBuf = Arrays.copyOf(lineBuf, cap);
            }
            lineBuf[len++] = (byte) c;
            if (len > maxLen) {
                throw new HttpException(400, "Header line too long");
            }
        }
    }

    /** Valid until the next {@link #readHeaderLine(int)}. */
    byte[] headerLineBuffer() {
        return lineBuf;
    }

    /**
     * If the bytes at the read cursor are the same header block as the previous
     * empty-body request on this connection, consume them and return that
     * request. A miss leaves the cursor where the parser can read normally.
     */
    HttpRequest tryRepeatedHeaders() throws IOException {
        byte[] prev = repeatedHeaders;
        HttpRequest req = repeatedRequest;
        if (prev == null || req == null || !req.canReuse()) {
            return null;
        }
        if (pos >= count) {
            int n = fillHeap();
            if (n <= 0) {
                return null;
            }
        }
        int avail = count - pos;
        if (avail < prev.length) {
            return null;
        }
        if (!Arrays.equals(buf, pos, pos + prev.length, prev, 0, prev.length)) {
            return null;
        }
        pos += prev.length;
        req.prepareForReuse();
        return req;
    }

    /** Note where the next header block starts so it can be saved if the parse stays in this buffer. */
    void markHeaderStart() {
        marking = true;
        headerMark = pos;
        headerEpoch = fillEpoch;
    }

    void endHeaderMark() {
        marking = false;
    }

    /**
     * Keep {@code req} for the next identical header block. No-op when the
     * bytes were overwritten by a later socket read, or when the request has
     * a body stream that cannot be replayed.
     */
    void rememberIfReusable(HttpRequest req) {
        if (req == null || !req.canReuse() || !marking) {
            return;
        }
        int start = headerMark;
        int end = pos;
        if (start < 0 || end <= start || end > count || headerEpoch != fillEpoch) {
            return;
        }
        int len = end - start;
        if (repeatedHeaders == null || repeatedHeaders.length != len) {
            repeatedHeaders = new byte[len];
        }
        System.arraycopy(buf, start, repeatedHeaders, 0, len);
        repeatedRequest = req;
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
        lineBuf = null;
        if (raw != null) {
            raw.close();
        }
    }

    private int fillHeap() throws IOException {
        int oldCount = count;
        int oldPos = pos;
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
            fillEpoch++;
            // The mark was taken on an empty buffer. The bytes just read are
            // the header block, starting at index 0.
            if (marking && oldPos >= oldCount && headerMark == oldCount) {
                headerMark = 0;
                headerEpoch = fillEpoch;
            }
            return n;
        }
        pos = 0;
        count = 0;
        fillEpoch++;
        return n;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}
