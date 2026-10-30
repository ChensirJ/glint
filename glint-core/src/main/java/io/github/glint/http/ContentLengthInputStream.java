package io.github.glint.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads at most {@code length} bytes from the underlying stream, then EOF.
 * Does not close the underlying stream (the connection owns it).
 */
final class ContentLengthInputStream extends InputStream {

    private final InputStream in;
    private long remaining;
    private boolean closed;

    ContentLengthInputStream(InputStream in, long length) {
        if (length < 0) {
            throw new IllegalArgumentException("length");
        }
        this.in = in;
        this.remaining = length;
    }

    long remaining() {
        return remaining;
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        if (remaining <= 0) {
            return -1;
        }
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected EOF in body");
        }
        remaining--;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (remaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int n = in.read(b, off, toRead);
        if (n < 0) {
            throw new EOFException("Unexpected EOF in body");
        }
        remaining -= n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        ensureOpen();
        if (n <= 0 || remaining <= 0) {
            return 0;
        }
        long skipped = in.skip(Math.min(n, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(remaining, in.available());
    }

    @Override
    public void close() {
        // Do not close the socket stream; unread bytes are drained by HttpRequest.
        closed = true;
    }

    /** Discard any unread body bytes so the next keep-alive request can be parsed. */
    void drain() throws IOException {
        if (remaining <= 0) {
            return;
        }
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                throw new EOFException("Unexpected EOF while draining body");
            }
            remaining -= n;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}