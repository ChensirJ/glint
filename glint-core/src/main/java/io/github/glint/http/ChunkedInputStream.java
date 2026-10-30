package io.github.glint.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes HTTP/1.1 chunked transfer encoding on the fly.
 * Enforces a maximum decoded body size. Does not close the underlying stream.
 * Trailer headers after the zero chunk are discarded.
 */
final class ChunkedInputStream extends InputStream {

    private final InputStream in;
    private final int maxBytes;
    private int chunkRemaining;
    private int totalRead;
    private boolean done;
    private boolean closed;
    ChunkedInputStream(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (done || len <= 0) {
            return done ? -1 : 0;
        }
        if (chunkRemaining == 0) {
            if (!nextChunk()) {
                return -1;
            }
        }
        int toRead = Math.min(len, chunkRemaining);
        int n = in.read(b, off, toRead);
        if (n < 0) {
            throw new EOFException("Unexpected EOF in chunk data");
        }
        chunkRemaining -= n;
        totalRead += n;
        if (chunkRemaining == 0) {
            consumeChunkCrlf();
        }
        return n;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Read and discard until the zero-size chunk (and trailers). */
    void drain() throws IOException {
        byte[] buf = new byte[8192];
        while (read(buf) >= 0) {
            // discard
        }
    }

    private boolean nextChunk() throws IOException {
        String sizeLine = readLine(4096);
        if (sizeLine == null) {
            throw new HttpException(400, "Unexpected EOF in chunk size");
        }
        int semi = sizeLine.indexOf(';');
        String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
        final int size;
        try {
            size = Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new HttpException(400, "Invalid chunk size");
        }
        if (size < 0) {
            throw new HttpException(400, "Negative chunk size");
        }
        if (size == 0) {
            while (true) {
                String trailer = readLine(8192);
                if (trailer == null || trailer.isEmpty()) {
                    break;
                }
            }
            done = true;
            return false;
        }
        if (totalRead + size > maxBytes) {
            throw new HttpException(413, "Request body too large");
        }
        chunkRemaining = size;
        return true;
    }

    private void consumeChunkCrlf() throws IOException {
        int c1 = in.read();
        if (c1 < 0) {
            throw new EOFException("Unexpected EOF after chunk");
        }
        if (c1 == '\n') {
            return;
        }
        if (c1 != '\r') {
            throw new HttpException(400, "Missing CRLF after chunk");
        }
        int c2 = in.read();
        if (c2 != '\n') {
            throw new HttpException(400, "Missing CRLF after chunk");
        }
    }

    private String readLine(int maxLen) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (buf.size() == 0 && prev < 0) {
                    return null;
                }
                break;
            }
            if (c == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (prev == '\r' && len > 0) {
                    len--;
                }
                return new String(bytes, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
            }
            buf.write(c);
            prev = c;
            if (buf.size() > maxLen) {
                throw new HttpException(400, "Header line too long");
            }
        }
        byte[] bytes = buf.toByteArray();
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}