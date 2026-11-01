package io.github.glint.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Connection-private response buffer.
 *
 * <p>One virtual thread writes HTTP responses. WebSocket sends from other
 * threads synchronize on this stream. The write methods themselves take no
 * monitor, so a blocking socket write cannot pin the carrier the way a
 * {@code synchronized} {@link java.io.BufferedOutputStream} subclass would.
 */
final class ConnectionOutputStream extends OutputStream {

    static final int BUFFER = 8 * 1024;

    private final OutputStream out;
    private final byte[] buf = new byte[BUFFER];
    private int count;
    private boolean closed;

    ConnectionOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        if (count >= buf.length) {
            flushBuffer();
        }
        buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }
        // Large payloads skip the copy and go straight to the socket.
        if (len >= buf.length) {
            flushBuffer();
            out.write(b, off, len);
            return;
        }
        if (len > buf.length - count) {
            flushBuffer();
        }
        System.arraycopy(b, 0, buf, count, len);
        count += len;
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        flushBuffer();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flushBuffer();
        } finally {
            out.close();
        }
    }

    private void flushBuffer() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}
